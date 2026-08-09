/*
 * CS1237 24-bit load cell ADC driver.
 *
 * Protocol summary (Chipsea CS1237 datasheet, "SCLK timing"):
 *
 *   DRDY/DOUT goes LOW when a conversion is ready. The master then clocks
 *   SCLK; DOUT changes on the rising edge, so it is sampled while SCLK is
 *   high.
 *
 *   Plain read              : clocks 1..24 = data, 25..27 = end of frame.
 *   Register read / write   : clocks 1..24 = data (discarded)
 *                             25..26 = filler
 *                             27     = chip releases DOUT (high impedance)
 *                             28..29 = bus turnaround, master may drive DOUT
 *                             30..36 = 7 bit command, MSB first
 *                             37     = turnaround
 *                             38..45 = 8 data bits
 *                             46     = chip takes DOUT back as an output
 *
 * SCLK must never stay HIGH for more than ~100 us or the chip powers down, so
 * interrupts are locked around the high phase of every pulse (~2 us windows).
 */

#include "cs1237.h"

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/util.h>
#include <errno.h>

LOG_MODULE_REGISTER(cs1237, CONFIG_SHELF_LOG_LEVEL);

/* Half of the SCLK period. The CS1237 accepts up to 1.1 MHz; ~250 kHz keeps a
 * comfortable margin while staying far below the 100 us power-down limit. */
#define SCK_HALF_US       2

/* A conversion takes 100 ms at the slowest (10 Hz) setting. */
#define READY_TIMEOUT_MS  400

#define CMD_READ_REG      0x56  /* 7 bit command 1010110b */
#define CMD_WRITE_REG     0x65  /* 7 bit command 1100101b */

#define DOUT_INPUT_FLAGS  (GPIO_INPUT | GPIO_PULL_UP)

/* Emit one SCLK pulse. Returns the DOUT level sampled while SCLK was high. */
static int sck_pulse(const struct cs1237 *dev, bool sample)
{
	int bit = 0;
	unsigned int key = irq_lock();

	gpio_pin_set_dt(&dev->sck, 1);
	k_busy_wait(SCK_HALF_US);
	if (sample) {
		bit = gpio_pin_get_dt(&dev->dout);
	}
	gpio_pin_set_dt(&dev->sck, 0);

	irq_unlock(key);
	k_busy_wait(SCK_HALF_US);

	return bit > 0 ? 1 : 0;
}

/* Drive one bit out on DOUT. The chip latches it on the falling edge. */
static void sck_pulse_write(const struct cs1237 *dev, int bit)
{
	gpio_pin_set_dt(&dev->dout, bit);
	(void)sck_pulse(dev, false);
}

static int wait_ready(const struct cs1237 *dev, uint32_t timeout_ms)
{
	int64_t deadline = k_uptime_get() + timeout_ms;

	do {
		if (gpio_pin_get_dt(&dev->dout) == 0) {
			return 0;
		}
		k_msleep(1);
	} while (k_uptime_get() < deadline);

	return -ETIMEDOUT;
}

static int32_t sign_extend24(uint32_t v)
{
	if (v & BIT(23)) {
		v |= 0xFF000000u;
	}
	return (int32_t)v;
}

int cs1237_read(const struct cs1237 *dev, int32_t *raw, uint32_t timeout_ms)
{
	uint32_t v = 0;
	int ret;

	ret = wait_ready(dev, timeout_ms);
	if (ret) {
		return ret;
	}

	for (int i = 0; i < 24; i++) {
		v = (v << 1) | (uint32_t)sck_pulse(dev, true);
	}
	/* Clocks 25..27 terminate the frame and release DRDY/DOUT high. */
	for (int i = 0; i < 3; i++) {
		(void)sck_pulse(dev, false);
	}

	if (v == 0x7FFFFFu || v == 0x800000u) {
		LOG_WRN("input saturated (raw 0x%06x) - check excitation and gain", v);
	}

	*raw = sign_extend24(v);
	return 0;
}

/* Shared implementation of the 46 clock register access frame. */
static int reg_xfer(const struct cs1237 *dev, bool write, uint8_t *val)
{
	uint8_t cmd = write ? CMD_WRITE_REG : CMD_READ_REG;
	int ret;

	ret = wait_ready(dev, READY_TIMEOUT_MS);
	if (ret) {
		return ret;
	}

	/* 1..26: conversion result plus filler, all discarded. */
	for (int i = 0; i < 26; i++) {
		(void)sck_pulse(dev, false);
	}
	/* 27: the chip releases DRDY/DOUT. */
	(void)sck_pulse(dev, false);

	/* 28..29: turnaround, the master owns DOUT from here. */
	gpio_pin_configure_dt(&dev->dout, GPIO_OUTPUT_INACTIVE);
	(void)sck_pulse(dev, false);
	(void)sck_pulse(dev, false);

	/* 30..36: 7 bit command, MSB first. */
	for (int i = 6; i >= 0; i--) {
		sck_pulse_write(dev, (cmd >> i) & 1);
	}

	if (write) {
		/* 37: turnaround. */
		(void)sck_pulse(dev, false);
		/* 38..45: register value, MSB first. */
		for (int i = 7; i >= 0; i--) {
			sck_pulse_write(dev, (*val >> i) & 1);
		}
		gpio_pin_configure_dt(&dev->dout, DOUT_INPUT_FLAGS);
	} else {
		/* Release DOUT before the chip starts driving it. */
		gpio_pin_configure_dt(&dev->dout, DOUT_INPUT_FLAGS);
		/* 37: turnaround. */
		(void)sck_pulse(dev, false);
		/* 38..45: register value, MSB first. */
		uint8_t r = 0;

		for (int i = 0; i < 8; i++) {
			r = (uint8_t)((r << 1) | sck_pulse(dev, true));
		}
		*val = r;
	}

	/* 46: the chip takes DRDY/DOUT back as an output. */
	(void)sck_pulse(dev, false);

	return 0;
}

int cs1237_read_config(const struct cs1237 *dev, uint8_t *cfg)
{
	return reg_xfer(dev, false, cfg);
}

int cs1237_write_config(const struct cs1237 *dev, uint8_t cfg)
{
	uint8_t v = cfg;

	return reg_xfer(dev, true, &v);
}

int cs1237_init(const struct cs1237 *dev)
{
	if (!gpio_is_ready_dt(&dev->pwr) || !gpio_is_ready_dt(&dev->sck) ||
	    !gpio_is_ready_dt(&dev->dout)) {
		return -ENODEV;
	}

	int ret = gpio_pin_configure_dt(&dev->pwr, GPIO_OUTPUT_INACTIVE);

	if (ret) {
		return ret;
	}

	/* Park the bus so nothing is back-fed into the unpowered chip. */
	(void)gpio_pin_configure_dt(&dev->sck, GPIO_DISCONNECTED);
	(void)gpio_pin_configure_dt(&dev->dout, GPIO_DISCONNECTED);

	return 0;
}

int cs1237_power_up(const struct cs1237 *dev)
{
	uint8_t cur;
	int ret;

	ret = gpio_pin_configure_dt(&dev->sck, GPIO_OUTPUT_INACTIVE);
	if (ret) {
		return ret;
	}
	ret = gpio_pin_configure_dt(&dev->dout, DOUT_INPUT_FLAGS);
	if (ret) {
		return ret;
	}

	gpio_pin_set_dt(&dev->pwr, 1);
	k_msleep(CONFIG_SHELF_LOADCELL_WARMUP_MS);

	ret = cs1237_read_config(dev, &cur);
	if (ret) {
		LOG_ERR("CS1237 not responding (%d) - rail, wiring or warm-up time", ret);
		return ret;
	}

	if (cur != dev->config) {
		LOG_DBG("config 0x%02x -> 0x%02x", cur, dev->config);
		ret = cs1237_write_config(dev, dev->config);
		if (ret) {
			return ret;
		}
		/* Let the modulator restart at the new rate. */
		k_msleep(50);
	}

	return 0;
}

void cs1237_power_down(const struct cs1237 *dev)
{
	gpio_pin_set_dt(&dev->sck, 0);
	(void)gpio_pin_configure_dt(&dev->dout, GPIO_DISCONNECTED);
	(void)gpio_pin_configure_dt(&dev->sck, GPIO_DISCONNECTED);
	gpio_pin_set_dt(&dev->pwr, 0);
}

int cs1237_read_avg(const struct cs1237 *dev, uint8_t discard, uint8_t samples,
		    int32_t *raw)
{
	int64_t acc = 0;
	int32_t v;
	int ret;

	if (samples == 0) {
		return -EINVAL;
	}

	for (uint8_t i = 0; i < discard; i++) {
		ret = cs1237_read(dev, &v, READY_TIMEOUT_MS);
		if (ret) {
			return ret;
		}
	}

	for (uint8_t i = 0; i < samples; i++) {
		ret = cs1237_read(dev, &v, READY_TIMEOUT_MS);
		if (ret) {
			return ret;
		}
		acc += v;
	}

	*raw = (int32_t)(acc / samples);
	return 0;
}
