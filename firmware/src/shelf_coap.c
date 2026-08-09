/*
 * CoAP client for the smart shelf node.
 *
 * The gateway address is not configured anywhere: requests start out as
 * realm-local multicast (ff03::1, reaches every node in the Thread mesh) and
 * as soon as the gateway answers a LED poll its unicast address is latched and
 * used from then on. If the gateway ever moves or stops answering, the node
 * falls back to multicast again.
 */

#include "shelf_coap.h"

#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/drivers/hwinfo.h>
#include <zephyr/net/socket.h>
#include <zephyr/net/coap.h>

#include <errno.h>
#include <stdio.h>
#include <string.h>

LOG_MODULE_REGISTER(shelf_coap, CONFIG_SHELF_LOG_LEVEL);

#define COAP_PORT          5683
/* Thread realm-local all-nodes: forwarded across the whole mesh. */
#define GW_MCAST_ADDR      "ff03::1"
#define MCAST_HOP_LIMIT    8

#define TX_BUF_SIZE        192
#define RX_BUF_SIZE        192
#define TOKEN_LEN          8

/* Consecutive unicast failures before falling back to multicast discovery. */
#define GW_LOST_THRESHOLD  3

static int sock = -1;
static K_MUTEX_DEFINE(sock_lock);

static struct sockaddr_in6 gw_addr;
static bool gw_known;
static uint8_t gw_miss;

static char dev_id[2 * 8 + 1];

const char *shelf_device_id(void)
{
	return dev_id;
}

static void init_device_id(void)
{
	uint8_t id[8] = { 0 };
	ssize_t len = hwinfo_get_device_id(id, sizeof(id));

	if (len <= 0) {
		len = 0;
	}

	for (ssize_t i = 0; i < len; i++) {
		(void)snprintf(&dev_id[i * 2], 3, "%02x", id[i]);
	}
	dev_id[len * 2] = '\0';

	if (dev_id[0] == '\0') {
		strcpy(dev_id, "unknown");
	}
}

int shelf_coap_init(void)
{
	struct sockaddr_in6 local = {
		.sin6_family = AF_INET6,
		.sin6_port = htons(0),
		.sin6_addr = in6addr_any,
	};
	int hops = MCAST_HOP_LIMIT;
	int ret;

	init_device_id();

	sock = zsock_socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP);
	if (sock < 0) {
		LOG_ERR("socket() failed (%d)", errno);
		return -errno;
	}

	ret = zsock_bind(sock, (struct sockaddr *)&local, sizeof(local));
	if (ret < 0) {
		LOG_ERR("bind() failed (%d)", errno);
		zsock_close(sock);
		sock = -1;
		return -errno;
	}

	/* Realm-local multicast has to survive more than one mesh hop. */
	(void)zsock_setsockopt(sock, IPPROTO_IPV6, IPV6_MULTICAST_HOPS,
			       &hops, sizeof(hops));

	LOG_INF("CoAP client ready, node id %s", dev_id);
	return 0;
}

static void gw_target(struct sockaddr_in6 *dst)
{
	memset(dst, 0, sizeof(*dst));
	dst->sin6_family = AF_INET6;
	dst->sin6_port = htons(COAP_PORT);

	if (gw_known) {
		memcpy(&dst->sin6_addr, &gw_addr.sin6_addr, sizeof(dst->sin6_addr));
	} else {
		(void)zsock_inet_pton(AF_INET6, GW_MCAST_ADDR, &dst->sin6_addr);
	}
}

/* Drop anything left over from a previous, timed out exchange. */
static void flush_rx(void)
{
	uint8_t scratch[8];

	while (zsock_recv(sock, scratch, sizeof(scratch), ZSOCK_MSG_DONTWAIT) > 0) {
		/* discard */
	}
}

static int send_request(uint8_t type, uint8_t method, const char *resource,
			const char *query, const uint8_t *payload, size_t payload_len,
			uint8_t token[TOKEN_LEN], struct sockaddr_in6 *dst)
{
	uint8_t buf[TX_BUF_SIZE];
	struct coap_packet req;
	int ret;

	memcpy(token, coap_next_token(), TOKEN_LEN);

	ret = coap_packet_init(&req, buf, sizeof(buf), COAP_VERSION_1, type,
			       TOKEN_LEN, token, method, coap_next_id());
	if (ret < 0) {
		return ret;
	}

	/* Option numbers must be appended in ascending order: PATH(11) < QUERY(15). */
	ret = coap_packet_append_option(&req, COAP_OPTION_URI_PATH,
					CONFIG_SHELF_COAP_BASE_PATH,
					strlen(CONFIG_SHELF_COAP_BASE_PATH));
	if (ret < 0) {
		return ret;
	}

	ret = coap_packet_append_option(&req, COAP_OPTION_URI_PATH, resource,
					strlen(resource));
	if (ret < 0) {
		return ret;
	}

	if (query != NULL) {
		ret = coap_packet_append_option(&req, COAP_OPTION_URI_QUERY, query,
						strlen(query));
		if (ret < 0) {
			return ret;
		}
	}

	if (payload != NULL && payload_len > 0) {
		ret = coap_packet_append_payload_marker(&req);
		if (ret < 0) {
			return ret;
		}
		ret = coap_packet_append_payload(&req, payload, payload_len);
		if (ret < 0) {
			return ret;
		}
	}

	gw_target(dst);

	ret = zsock_sendto(sock, req.data, req.offset, 0,
			   (struct sockaddr *)dst, sizeof(*dst));
	if (ret < 0) {
		return -errno;
	}

	return 0;
}

int shelf_coap_report_weight(int32_t raw, int32_t milligram, uint32_t seq,
			     int32_t batt_mv)
{
	char payload[TX_BUF_SIZE - 64];
	uint8_t token[TOKEN_LEN];
	struct sockaddr_in6 dst;
	int len;
	int ret;

	if (sock < 0) {
		return -ENOTCONN;
	}

	len = snprintk(payload, sizeof(payload),
		       "{\"id\":\"%s\",\"seq\":%u,\"raw\":%d,\"mg\":%d,\"mv\":%d}",
		       dev_id, seq, raw, milligram, batt_mv);
	if (len < 0 || len >= (int)sizeof(payload)) {
		return -ENOMEM;
	}

	k_mutex_lock(&sock_lock, K_FOREVER);
	ret = send_request(COAP_TYPE_NON_CON, COAP_METHOD_POST, "weight", NULL,
			   (const uint8_t *)payload, len, token, &dst);
	k_mutex_unlock(&sock_lock);

	if (ret) {
		LOG_WRN("weight report failed (%d)", ret);
	} else {
		LOG_INF("report seq=%u raw=%d mg=%d mv=%d", seq, raw, milligram,
			batt_mv);
	}

	return ret;
}

/*
 * Accept "0"/"1", "on"/"off", {"led":1}, {"led":true} and friends.
 */
static bool parse_led_payload(const uint8_t *p, uint16_t len, bool *led_on)
{
	char text[33];
	size_t n = MIN(len, sizeof(text) - 1);
	const char *cursor;

	if (p == NULL || len == 0) {
		return false;
	}

	memcpy(text, p, n);
	text[n] = '\0';

	/* If the payload names the field, only look after it. */
	cursor = strstr(text, "led");
	if (cursor != NULL) {
		cursor += 3;
	} else {
		cursor = text;
	}

	for (; *cursor != '\0'; cursor++) {
		switch (*cursor) {
		case '0':
			*led_on = false;
			return true;
		case '1':
			*led_on = true;
			return true;
		case 't':
		case 'T':
			*led_on = true;   /* true */
			return true;
		case 'f':
		case 'F':
			*led_on = false;  /* false */
			return true;
		case 'o':
		case 'O':
			/* "on" vs "off" */
			*led_on = (cursor[1] != 'f' && cursor[1] != 'F');
			return true;
		default:
			break;
		}
	}

	return false;
}

static int wait_response(const uint8_t token[TOKEN_LEN], bool *led_on,
			 struct sockaddr_in6 *from)
{
	uint8_t buf[RX_BUF_SIZE];
	struct zsock_pollfd fds = { .fd = sock, .events = ZSOCK_POLLIN };
	int64_t deadline = k_uptime_get() + CONFIG_SHELF_COAP_RESP_TIMEOUT_MS;

	while (true) {
		int64_t remaining = deadline - k_uptime_get();
		struct coap_packet rsp;
		uint8_t rtoken[COAP_TOKEN_MAX_LEN];
		socklen_t from_len = sizeof(*from);
		const uint8_t *payload;
		uint16_t payload_len;
		int received;
		int ret;

		if (remaining <= 0) {
			return -EAGAIN;
		}

		ret = zsock_poll(&fds, 1, (int)remaining);
		if (ret <= 0) {
			return -EAGAIN;
		}

		received = zsock_recvfrom(sock, buf, sizeof(buf), 0,
					  (struct sockaddr *)from, &from_len);
		if (received < 0) {
			return -errno;
		}

		ret = coap_packet_parse(&rsp, buf, received, NULL, 0);
		if (ret < 0) {
			continue;
		}

		/* Ignore late answers to an earlier poll. */
		if (coap_header_get_token(&rsp, rtoken) != TOKEN_LEN ||
		    memcmp(rtoken, token, TOKEN_LEN) != 0) {
			continue;
		}

		if (coap_header_get_code(&rsp) != COAP_RESPONSE_CODE_CONTENT) {
			LOG_WRN("gateway answered %d.%02d",
				coap_header_get_code(&rsp) >> 5,
				coap_header_get_code(&rsp) & 0x1f);
			return -EAGAIN;
		}

		payload = coap_packet_get_payload(&rsp, &payload_len);
		if (!parse_led_payload(payload, payload_len, led_on)) {
			LOG_WRN("unparsable LED payload (%u bytes)", payload_len);
			return -EAGAIN;
		}

		return 0;
	}
}

int shelf_coap_poll_led(bool *led_on)
{
	char query[16 + sizeof(dev_id)];
	uint8_t token[TOKEN_LEN];
	struct sockaddr_in6 dst;
	struct sockaddr_in6 from;
	int ret;

	if (sock < 0) {
		return -ENOTCONN;
	}

	(void)snprintk(query, sizeof(query), "id=%s", dev_id);

	k_mutex_lock(&sock_lock, K_FOREVER);

	flush_rx();

	ret = send_request(COAP_TYPE_CON, COAP_METHOD_GET, "led", query, NULL, 0,
			   token, &dst);
	if (ret == 0) {
		ret = wait_response(token, led_on, &from);
	}

	if (ret == 0) {
		if (!gw_known) {
			char addr[NET_IPV6_ADDR_LEN];

			memcpy(&gw_addr, &from, sizeof(gw_addr));
			gw_addr.sin6_port = htons(COAP_PORT);
			gw_known = true;
			LOG_INF("gateway found at %s",
				zsock_inet_ntop(AF_INET6, &gw_addr.sin6_addr, addr,
						sizeof(addr)));
		}
		gw_miss = 0;
	} else if (gw_known) {
		if (++gw_miss >= GW_LOST_THRESHOLD) {
			LOG_WRN("gateway unresponsive, back to multicast discovery");
			gw_known = false;
			gw_miss = 0;
		}
	}

	k_mutex_unlock(&sock_lock);

	return ret;
}
