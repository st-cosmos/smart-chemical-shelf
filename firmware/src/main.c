/*
 * Smart shelf node.
 *
 *   E73-2G4M08S1C (nRF52840) + CS1237 load cell ADC, Thread / CoAP uplink.
 *
 *   measure thread : powers the load cell rail, averages a few CS1237 samples,
 *                    powers it back down and reports to the gateway whenever
 *                    the reading moved by more than the configured threshold.
 *   led thread     : polls the gateway every 700 ms and drives the LED.
 */

#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/logging/log.h>
#include <zephyr/net/net_event.h>
#include <zephyr/net/net_mgmt.h>
#include <errno.h>

#if defined(CONFIG_USB_DEVICE_STACK)
#include <zephyr/usb/usb_device.h>
#endif

#include "cs1237.h"
#include "shelf_cal.h"
#include "shelf_coap.h"

LOG_MODULE_REGISTER(shelf, CONFIG_SHELF_LOG_LEVEL);

#define SHELF_USER_NODE DT_PATH(zephyr_user)

BUILD_ASSERT(CONFIG_SHELF_CAL_COUNTS_PER_KG != 0,
	     "SHELF_CAL_COUNTS_PER_KG must not be zero");

static const struct gpio_dt_spec led =
	GPIO_DT_SPEC_GET(DT_ALIAS(shelf_led), gpios);

static const struct cs1237 loadcell = {
	.sck  = GPIO_DT_SPEC_GET(SHELF_USER_NODE, cs1237_sck_gpios),
	.dout = GPIO_DT_SPEC_GET(SHELF_USER_NODE, cs1237_dout_gpios),
	.pwr  = GPIO_DT_SPEC_GET(SHELF_USER_NODE, loadcell_power_gpios),
	.config = CS1237_SPEED_40 | CS1237_PGA_128 | CS1237_CH_A,
};

static K_SEM_DEFINE(net_ready, 0, 1);
static struct net_mgmt_event_callback l4_cb;

/* Upper bound on how long a worker waits for the first L4_CONNECTED event.
 * After that it starts anyway - CoAP simply fails until the mesh is up, which
 * is preferable to a node that stays silent forever if the event is missed. */
#define NET_READY_GRACE K_SECONDS(120)

static void wait_for_network(const char *who)
{
	if (k_sem_take(&net_ready, NET_READY_GRACE) != 0) {
		LOG_WRN("%s: no Thread connectivity yet, starting anyway", who);
	} else {
		/* L4_CONNECTED gives the semaphore once, but the measure and LED
		 * threads both wait on it - hand the token back so the second
		 * waiter is released too instead of eating the full grace time. */
		k_sem_give(&net_ready);
	}
}

/* ------------------------------------------------------------------ network */

static void l4_event_handler(struct net_mgmt_event_callback *cb, uint64_t event,
			     struct net_if *iface)
{
	ARG_UNUSED(cb);
	ARG_UNUSED(iface);

	switch (event) {
	case NET_EVENT_L4_CONNECTED:
		LOG_INF("Thread network connected");
		k_sem_give(&net_ready);
		break;
	case NET_EVENT_L4_DISCONNECTED:
		LOG_WRN("Thread network disconnected");
		break;
	default:
		break;
	}
}

/* ------------------------------------------------------------------ weight */

static uint32_t abs_delta(int32_t a, int32_t b)
{
	return (uint32_t)((a > b) ? (a - b) : (b - a));
}

/* The measurement thread and the `shelf` shell commands both drive the rail and
 * the bit-banged bus, so reads have to be serialised. */
static K_MUTEX_DEFINE(loadcell_lock);

int shelf_read_raw(int32_t *raw)
{
	int ret;

	k_mutex_lock(&loadcell_lock, K_FOREVER);

	ret = cs1237_power_up(&loadcell);
	if (ret == 0) {
		ret = cs1237_read_avg(&loadcell, CONFIG_SHELF_ADC_DISCARD_SAMPLES,
				      CONFIG_SHELF_ADC_AVG_SAMPLES, raw);
	}
	cs1237_power_down(&loadcell);

	k_mutex_unlock(&loadcell_lock);
	return ret;
}

static void measure_thread_fn(void *p1, void *p2, void *p3)
{
	int32_t last_reported = 0;
	bool have_last = false;
	uint32_t seq = 0;
	uint32_t since_report = 0;
	int64_t next;

	ARG_UNUSED(p1);
	ARG_UNUSED(p2);
	ARG_UNUSED(p3);

	wait_for_network("measure");
	next = k_uptime_get();

	while (true) {
		int32_t raw = 0;
		int ret;

		next += CONFIG_SHELF_MEASURE_INTERVAL_MS;

		ret = shelf_read_raw(&raw);

		if (ret) {
			LOG_ERR("load cell measurement failed (%d)", ret);
		} else {
			bool changed = !have_last ||
				abs_delta(raw, last_reported) >=
					(uint32_t)CONFIG_SHELF_ADC_DELTA_THRESHOLD;
			bool heartbeat;

			since_report++;
			heartbeat = (CONFIG_SHELF_HEARTBEAT_PERIOD > 0) &&
				(since_report >= (uint32_t)CONFIG_SHELF_HEARTBEAT_PERIOD);

			LOG_DBG("raw=%d mg=%d%s", raw, shelf_raw_to_mg(raw),
				changed ? " (changed)" : "");

			if (changed || heartbeat) {
				int32_t batt_mv = -1;

				(void)shelf_battery_read_mv(&batt_mv);

				if (shelf_coap_report_weight(raw, shelf_raw_to_mg(raw),
							     ++seq, batt_mv) == 0) {
					last_reported = raw;
					have_last = true;
					since_report = 0;
				}
			}
		}

		k_sleep(K_TIMEOUT_ABS_MS(next));
	}
}

/* --------------------------------------------------------------------- LED */

static void led_thread_fn(void *p1, void *p2, void *p3)
{
	bool led_state = false;
	uint32_t misses = 0;
	int64_t next;

	ARG_UNUSED(p1);
	ARG_UNUSED(p2);
	ARG_UNUSED(p3);

	wait_for_network("led");
	next = k_uptime_get();

	while (true) {
		bool want = led_state;

		next += CONFIG_SHELF_LED_POLL_INTERVAL_MS;

		if (shelf_coap_poll_led(&want) == 0) {
			misses = 0;
			if (want != led_state) {
				led_state = want;
				gpio_pin_set_dt(&led, led_state);
				LOG_INF("LED %s", led_state ? "on" : "off");
			}
		} else {
			misses++;
			if (CONFIG_SHELF_LED_FAILSAFE_POLLS > 0 &&
			    misses == (uint32_t)CONFIG_SHELF_LED_FAILSAFE_POLLS &&
			    led_state) {
				LOG_WRN("no gateway for %u polls, LED off", misses);
				led_state = false;
				gpio_pin_set_dt(&led, 0);
			}
		}

		k_sleep(K_TIMEOUT_ABS_MS(next));
	}
}

/* ----------------------------------------------------------------- battery */

/* SAADC channel on the internal VDDH/5 tap (see the board overlay). VDDH is
 * +BAT_FIN, so this reads the battery when running from it - and VBUS minus
 * the D1 drop when USB is plugged in and Q5 has the battery disconnected. */
static const struct adc_dt_spec vbat_adc =
	ADC_DT_SPEC_GET_BY_IDX(SHELF_USER_NODE, 0);

int shelf_battery_read_mv(int32_t *mv)
{
	int16_t buf;
	struct adc_sequence seq = {
		.buffer = &buf,
		.buffer_size = sizeof(buf),
	};
	int32_t val;
	int ret;

	if (!adc_is_ready_dt(&vbat_adc)) {
		return -ENODEV;
	}

	ret = adc_channel_setup_dt(&vbat_adc);
	if (ret) {
		return ret;
	}

	ret = adc_sequence_init_dt(&vbat_adc, &seq);
	if (ret) {
		return ret;
	}

	ret = adc_read_dt(&vbat_adc, &seq);
	if (ret) {
		return ret;
	}

	val = buf;
	ret = adc_raw_to_millivolts_dt(&vbat_adc, &val);
	if (ret) {
		return ret;
	}

	*mv = val * 5;	/* the tap divides VDDH by 5 */
	return 0;
}

/* Software-PWM breathing test for the LED hardware (Q1 gate on P0.06). The
 * shell thread bit-bangs the duty cycle, so no PWM peripheral or devicetree
 * channel is needed. The LED thread may write the pin once mid-test (700 ms
 * poll); the next PWM period overwrites it, so a glitch is at most 2 ms. */
int shelf_led_breathe(unsigned int cycles)
{
	const uint32_t period_us = 2000;      /* 500 Hz, flicker-free */
	const uint32_t periods_per_step = 4;  /* ~8 ms per brightness step */

	for (unsigned int c = 0; c < cycles; c++) {
		for (int step = 0; step < 510; step++) {
			uint32_t b = (step < 255) ? step : 510 - step;
			/* b^2/255 approximates the eye's response, so the ramp
			 * looks linear instead of hanging near full brightness. */
			uint32_t on_us = (b * b * period_us) / (255U * 255U);

			for (uint32_t p = 0; p < periods_per_step; p++) {
				if (on_us > 0) {
					gpio_pin_set_dt(&led, 1);
					k_busy_wait(on_us);
				}
				if (on_us < period_us) {
					gpio_pin_set_dt(&led, 0);
					k_busy_wait(period_us - on_us);
				}
			}
		}
	}

	gpio_pin_set_dt(&led, 0);
	return 0;
}

/* -------------------------------------------------------------------- main */

K_THREAD_DEFINE(measure_tid, 3072, measure_thread_fn, NULL, NULL, NULL,
		K_PRIO_PREEMPT(7), 0, 0);
K_THREAD_DEFINE(led_tid, 3072, led_thread_fn, NULL, NULL, NULL,
		K_PRIO_PREEMPT(6), 0, 0);

int main(void)
{
	int ret;

#if defined(CONFIG_USB_DEVICE_STACK)
	/* Console and shell live on the USB-C port. */
	(void)usb_enable(NULL);
#endif

	LOG_INF("smart shelf node starting");

	if (!gpio_is_ready_dt(&led)) {
		LOG_ERR("LED GPIO not ready");
		return -ENODEV;
	}

	ret = gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE);
	if (ret) {
		LOG_ERR("LED configure failed (%d)", ret);
		return ret;
	}

	ret = cs1237_init(&loadcell);
	if (ret) {
		LOG_ERR("CS1237 GPIO init failed (%d)", ret);
		return ret;
	}

	/* Non-fatal: without stored values the Kconfig defaults stay in effect,
	 * which is still a working (if uncalibrated) node. */
	(void)shelf_cal_init();

	ret = shelf_coap_init();
	if (ret) {
		LOG_ERR("CoAP init failed (%d)", ret);
		return ret;
	}

	net_mgmt_init_event_callback(&l4_cb, l4_event_handler,
				     NET_EVENT_L4_CONNECTED | NET_EVENT_L4_DISCONNECTED);
	net_mgmt_add_event_callback(&l4_cb);

	LOG_INF("waiting for the Thread network");
	return 0;
}
