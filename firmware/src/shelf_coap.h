/*
 * CoAP client used to talk to the shelf gateway over Thread.
 */

#ifndef SHELF_COAP_H_
#define SHELF_COAP_H_

#include <stdbool.h>
#include <stdint.h>

/**
 * Open the UDP socket. Safe to call before the Thread network is up.
 */
int shelf_coap_init(void);

/**
 * NON-confirmable POST of one measurement to <base>/weight.
 *
 * Payload: {"id":"<eui64>","seq":N,"raw":N,"mg":N,"mv":N}
 *
 * @param batt_mv VDDH millivolts (the battery when running from it), or a
 *                negative value when the reading failed - sent as-is so the
 *                gateway can tell "unknown" from a real voltage.
 */
int shelf_coap_report_weight(int32_t raw, int32_t milligram, uint32_t seq,
			     int32_t batt_mv);

/**
 * Confirmable GET of <base>/led?id=<eui64>, waits up to
 * CONFIG_SHELF_COAP_RESP_TIMEOUT_MS for the answer.
 *
 * The gateway replies with 2.05 Content and a payload the parser accepts in
 * any of these forms: "0" / "1", "on" / "off", {"led":1}, {"led":true}.
 *
 * @retval 0        @p led_on holds the commanded state
 * @retval -EAGAIN  no usable answer within the timeout
 */
int shelf_coap_poll_led(bool *led_on);

/**
 * Human readable node id (EUI64 style hex string) used in the payloads.
 */
const char *shelf_device_id(void);

#endif /* SHELF_COAP_H_ */
