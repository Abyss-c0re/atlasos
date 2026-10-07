/*
 * titan_dhcp_gw — L2 DHCP server for the Titan hotspot.
 *
 * Android network_stack owns UDP/67. Its replies are dropped: they advertise
 * the wrong prefix (and a .0 gateway), and Tailscale policy routing returns
 * EPERM for anything that does leave the UDP socket. This process injects
 * OFFER/ACK as Ethernet frames on the bridge, so the client gets 192.168.6.x
 * with gateway 192.168.6.1.
 *
 *   titan_dhcp_gw <ifname> [router/prefix]
 *   titan_dhcp_gw --selftest
 *   titan_dhcp_gw --probe <ifname>
 *
 * Rebuild (BlackCube, same NDK as openwrt-lpctl):
 *   aarch64-linux-android28-clang -O2 -fPIE -pie -D_GNU_SOURCE \
 *     -o titan_dhcp_gw titan_dhcp_gw.c
 */
#include <arpa/inet.h>
#include <errno.h>
#include <ifaddrs.h>
#include <linux/if_packet.h>
#include <net/if.h>
#include <netinet/if_ether.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define LEASE_PATH "/data/local/tmp/titan_dhcp_gw.leases"
#define LEASE_SEC 43200u
#define POOL_LO 10u
#define POOL_HI 250u
#define MAX_LEASES 64
#define BOOT_MIN 300

static const char *lease_path = LEASE_PATH;
static volatile sig_atomic_t stop_flag;
static uint32_t router_h;
static uint32_t mask_h;
static int prefix_len = 24;

struct lease {
    uint8_t mac[6];
    uint32_t ip;
    uint32_t exp;
    int used;
};
static struct lease leases[MAX_LEASES];

struct dhcp_view {
    int ok;
    uint32_t xid;
    uint16_t flags;
    uint32_t ciaddr;
    uint8_t mac[6];
    uint8_t chaddr[16];
    uint8_t msg;
    int has_req;
    int has_sid;
    uint32_t req_ip;
    uint32_t sid;
};

static void on_sig(int s) {
    (void)s;
    stop_flag = 1;
}

static uint32_t rd32(const uint8_t *p) {
    uint32_t v;
    memcpy(&v, p, 4);
    return ntohl(v);
}
static uint16_t rd16(const uint8_t *p) {
    uint16_t v;
    memcpy(&v, p, 2);
    return ntohs(v);
}
static void wr32(uint8_t *p, uint32_t host) {
    uint32_t v = htonl(host);
    memcpy(p, &v, 4);
}
static void wr16(uint8_t *p, uint16_t host) {
    uint16_t v = htons(host);
    memcpy(p, &v, 2);
}

static uint16_t ip_checksum(const uint8_t *p, int len) {
    uint32_t sum = 0;
    while (len > 1) {
        uint16_t w;
        memcpy(&w, p, 2);
        sum += w;
        p += 2;
        len -= 2;
    }
    if (len) {
        uint16_t w = 0;
        memcpy(&w, p, 1);
        sum += w;
    }
    while (sum >> 16)
        sum = (sum & 0xffffu) + (sum >> 16);
    return (uint16_t)~sum;
}

static int prefix_ok(int p) { return p >= 8 && p <= 30; }

static uint32_t mask_from_prefix(int p) {
    if (p <= 0) return 0;
    if (p >= 32) return 0xffffffffu;
    return 0xffffffffu << (32 - p);
}

static int parse_router(const char *s) {
    int a, b, c, d, p = 24;
    int n = sscanf(s, "%d.%d.%d.%d/%d", &a, &b, &c, &d, &p);
    if (n < 4) return -1;
    if ((unsigned)a > 255 || (unsigned)b > 255 || (unsigned)c > 255 || (unsigned)d > 255)
        return -1;
    if (!prefix_ok(p)) return -1;
    router_h = ((uint32_t)a << 24) | ((uint32_t)b << 16) | ((uint32_t)c << 8) | (uint32_t)d;
    prefix_len = p;
    mask_h = mask_from_prefix(p);
    return 0;
}

static int pick_router(const char *ifname) {
    struct ifaddrs *ifa = NULL, *p;
    uint32_t best_ip = 0, best_mask = 0;
    int best = -1000000;
    int found = 0;
    if (getifaddrs(&ifa) != 0) return -1;
    for (p = ifa; p; p = p->ifa_next) {
        struct sockaddr_in *sa, *sm;
        uint32_t ip, mask;
        int score;
        if (!p->ifa_addr || p->ifa_addr->sa_family != AF_INET) continue;
        if (strcmp(p->ifa_name, ifname) != 0) continue;
        sa = (struct sockaddr_in *)p->ifa_addr;
        ip = ntohl(sa->sin_addr.s_addr);
        mask = 0xffffff00u;
        if (p->ifa_netmask && p->ifa_netmask->sa_family == AF_INET) {
            sm = (struct sockaddr_in *)p->ifa_netmask;
            mask = ntohl(sm->sin_addr.s_addr);
        }
        score = 0;
        if ((ip & ~mask) == 1) score += 10;
        if (ip == 0xc0a80601u) score += 50; /* 192.168.6.1 */
        if ((ip & 0xff000000u) == 0x0a000000u) score -= 5;
        if (!found || score > best) {
            best = score;
            best_ip = ip;
            best_mask = mask;
            found = 1;
        }
    }
    freeifaddrs(ifa);
    if (!found) return -1;
    router_h = best_ip;
    mask_h = best_mask ? best_mask : 0xffffff00u;
    prefix_len = 0;
    {
        uint32_t m = mask_h;
        while (m) {
            prefix_len += m >> 31;
            m <<= 1;
        }
    }
    return 0;
}

static void fmt_ip(uint32_t host, char *buf, int n) {
    snprintf(buf, (size_t)n, "%u.%u.%u.%u",
             (host >> 24) & 255, (host >> 16) & 255, (host >> 8) & 255, host & 255);
}

static int in_pool(uint32_t ip) {
    uint32_t host;
    if ((ip & mask_h) != (router_h & mask_h)) return 0;
    if (ip == router_h) return 0;
    /* Pool is the last octet .10–.250 of the router subnet. Product LAN is /24. */
    host = ip & 0xffu;
    return host >= POOL_LO && host <= POOL_HI;
}

static void load_leases(void) {
    FILE *f = fopen(lease_path, "r");
    char line[128];
    uint32_t now = (uint32_t)time(NULL);
    if (!f) return;
    while (fgets(line, sizeof line, f)) {
        unsigned a, b, c, d, e, fmac, ipb[4], exp;
        struct lease *slot = NULL;
        int i;
        if (sscanf(line, "%x:%x:%x:%x:%x:%x %u.%u.%u.%u %u",
                   &a, &b, &c, &d, &e, &fmac, &ipb[0], &ipb[1], &ipb[2], &ipb[3], &exp) != 11)
            continue;
        if (exp <= now) continue;
        for (i = 0; i < MAX_LEASES; i++) {
            if (!leases[i].used) { slot = &leases[i]; break; }
        }
        if (!slot) break;
        slot->used = 1;
        slot->mac[0] = (uint8_t)a; slot->mac[1] = (uint8_t)b; slot->mac[2] = (uint8_t)c;
        slot->mac[3] = (uint8_t)d; slot->mac[4] = (uint8_t)e; slot->mac[5] = (uint8_t)fmac;
        slot->ip = (ipb[0] << 24) | (ipb[1] << 16) | (ipb[2] << 8) | ipb[3];
        slot->exp = exp;
    }
    fclose(f);
}

static void save_leases(void) {
    char tmp[256];
    FILE *f;
    int i;
    snprintf(tmp, sizeof tmp, "%s.tmp", lease_path);
    f = fopen(tmp, "w");
    if (!f) return;
    for (i = 0; i < MAX_LEASES; i++) {
        if (!leases[i].used) continue;
        fprintf(f, "%02x:%02x:%02x:%02x:%02x:%02x %u.%u.%u.%u %u\n",
                leases[i].mac[0], leases[i].mac[1], leases[i].mac[2],
                leases[i].mac[3], leases[i].mac[4], leases[i].mac[5],
                (leases[i].ip >> 24) & 255, (leases[i].ip >> 16) & 255,
                (leases[i].ip >> 8) & 255, leases[i].ip & 255, leases[i].exp);
    }
    fclose(f);
    rename(tmp, lease_path);
}

static struct lease *find_mac(const uint8_t mac[6]) {
    int i;
    uint32_t now = (uint32_t)time(NULL);
    for (i = 0; i < MAX_LEASES; i++) {
        if (!leases[i].used) continue;
        if (leases[i].exp <= now) { leases[i].used = 0; continue; }
        if (memcmp(leases[i].mac, mac, 6) == 0) return &leases[i];
    }
    return NULL;
}

static struct lease *find_ip(uint32_t ip) {
    int i;
    uint32_t now = (uint32_t)time(NULL);
    for (i = 0; i < MAX_LEASES; i++) {
        if (!leases[i].used) continue;
        if (leases[i].exp <= now) { leases[i].used = 0; continue; }
        if (leases[i].ip == ip) return &leases[i];
    }
    return NULL;
}

static struct lease *take_slot(void) {
    int i;
    for (i = 0; i < MAX_LEASES; i++)
        if (!leases[i].used) return &leases[i];
    return NULL;
}

static void bind_lease(const uint8_t mac[6], uint32_t ip) {
    struct lease *l = find_mac(mac);
    struct lease *other;
    if (!l) l = take_slot();
    if (!l) return;
    other = find_ip(ip);
    if (other && other != l) other->used = 0;
    l->used = 1;
    memcpy(l->mac, mac, 6);
    l->ip = ip;
    l->exp = (uint32_t)time(NULL) + LEASE_SEC;
    save_leases();
}

static struct lease *alloc_lease(const uint8_t mac[6]) {
    struct lease *l = find_mac(mac);
    uint32_t h = 2166136261u;
    uint32_t span = POOL_HI - POOL_LO + 1;
    uint32_t probe, host, ip;
    int i;
    if (l && in_pool(l->ip)) {
        l->exp = (uint32_t)time(NULL) + LEASE_SEC;
        save_leases();
        return l;
    }
    for (i = 0; i < 6; i++) h = (h ^ mac[i]) * 16777619u;
    for (probe = 0; probe < span; probe++) {
        host = POOL_LO + ((h + probe) % span);
        ip = (router_h & 0xffffff00u) | host;
        if (!in_pool(ip)) continue;
        if (find_ip(ip)) continue;
        bind_lease(mac, ip);
        return find_mac(mac);
    }
    return NULL;
}

static void free_mac(const uint8_t mac[6]) {
    struct lease *l = find_mac(mac);
    if (!l) return;
    l->used = 0;
    save_leases();
}

static int parse_dhcp(const uint8_t *pkt, int len, struct dhcp_view *v) {
    int ihl, opt, end;
    const uint8_t *b;
    memset(v, 0, sizeof *v);
    if (len < 20 + 8 + 240) return 0;
    if ((pkt[0] >> 4) != 4) return 0;
    ihl = (pkt[0] & 0x0f) * 4;
    if (ihl < 20 || len < ihl + 8) return 0;
    if (pkt[9] != 17) return 0;
    if (rd16(pkt + ihl + 2) != 67) return 0;
    b = pkt + ihl + 8;
    end = len - (ihl + 8);
    if (end < 240) return 0;
    if (b[0] != 1 || b[1] != 1 || b[2] != 6) return 0;
    if (rd32(b + 236) != 0x63825363u) return 0;
    v->xid = rd32(b + 4);
    v->flags = rd16(b + 10);
    v->ciaddr = rd32(b + 12);
    memcpy(v->chaddr, b + 28, 16);
    memcpy(v->mac, b + 28, 6);
    opt = 240;
    while (opt < end) {
        uint8_t code = b[opt];
        uint8_t olen;
        if (code == 0) { opt++; continue; }
        if (code == 255) break;
        if (opt + 1 >= end) break;
        olen = b[opt + 1];
        if (opt + 2 + olen > end) break;
        if (code == 53 && olen == 1) v->msg = b[opt + 2];
        else if (code == 50 && olen == 4) {
            v->has_req = 1;
            v->req_ip = rd32(b + opt + 2);
        } else if (code == 54 && olen == 4) {
            v->has_sid = 1;
            v->sid = rd32(b + opt + 2);
        }
        opt += 2 + olen;
    }
    if (v->msg == 0) return 0;
    v->ok = 1;
    return 1;
}

static int put_opt(uint8_t *o, int n, int cap, uint8_t code, const void *data, int dlen) {
    if (n < 0 || n + 2 + dlen > cap) return -1;
    o[n] = code;
    o[n + 1] = (uint8_t)dlen;
    memcpy(o + n + 2, data, (size_t)dlen);
    return n + 2 + dlen;
}

/* Returns ethernet-payload length (IP packet). bcast selects 255.255.255.255. */
static int build_reply(const struct dhcp_view *v, int mtype, uint32_t yiaddr, int bcast,
                       uint8_t *out, int cap) {
    uint8_t *ip = out;
    uint8_t *udp;
    uint8_t *b;
    uint8_t opts[128];
    int on = 0;
    int boot_len, udp_len, ip_len;
    uint32_t ipdst, lease = LEASE_SEC;
    uint8_t u8;
    uint8_t dns[4], maskb[4], rtr[4], sid[4], bc[4];
    uint32_t bcast_addr = (router_h & mask_h) | ~mask_h;

    if (mtype == 6) bcast = 1;
    ipdst = bcast ? 0xffffffffu : (v->ciaddr ? v->ciaddr : yiaddr);
    wr32(rtr, router_h);
    wr32(maskb, mask_h);
    wr32(sid, router_h);
    wr32(dns, router_h);
    wr32(bc, bcast_addr);

    u8 = (uint8_t)mtype;
    on = put_opt(opts, on, (int)sizeof opts, 53, &u8, 1);
    on = put_opt(opts, on, (int)sizeof opts, 54, sid, 4);
    if (mtype != 6) {
        uint8_t lt[4];
        wr32(lt, lease);
        on = put_opt(opts, on, (int)sizeof opts, 51, lt, 4);
        on = put_opt(opts, on, (int)sizeof opts, 1, maskb, 4);
        on = put_opt(opts, on, (int)sizeof opts, 3, rtr, 4);
        on = put_opt(opts, on, (int)sizeof opts, 6, dns, 4);
        on = put_opt(opts, on, (int)sizeof opts, 28, bc, 4);
    } else {
        const char *msg = "wrong network";
        on = put_opt(opts, on, (int)sizeof opts, 56, msg, (int)strlen(msg));
    }
    if (on < 0 || on + 1 > (int)sizeof opts) return -1;
    opts[on++] = 255;

    boot_len = 240 + on;
    if (boot_len < BOOT_MIN) boot_len = BOOT_MIN;
    udp_len = 8 + boot_len;
    ip_len = 20 + udp_len;
    if (ip_len > cap) return -1;
    memset(out, 0, (size_t)ip_len);

    ip[0] = 0x45;
    ip[1] = 0;
    wr16(ip + 2, (uint16_t)ip_len);
    wr16(ip + 4, (uint16_t)(v->xid & 0xffffu));
    ip[8] = 64;
    ip[9] = 17;
    wr32(ip + 12, router_h);
    wr32(ip + 16, ipdst);
    {
        uint16_t c = ip_checksum(ip, 20);
        memcpy(ip + 10, &c, 2);
    }

    udp = ip + 20;
    wr16(udp + 0, 67);
    wr16(udp + 2, 68);
    wr16(udp + 4, (uint16_t)udp_len);

    b = udp + 8;
    b[0] = 2;
    b[1] = 1;
    b[2] = 6;
    wr32(b + 4, v->xid);
    wr16(b + 10, bcast ? 0x8000u : 0);
    if (mtype != 6 && v->ciaddr) wr32(b + 12, v->ciaddr);
    if (mtype != 6) wr32(b + 16, yiaddr);
    wr32(b + 20, router_h);
    memcpy(b + 28, v->chaddr, 16);
    wr32(b + 236, 0x63825363u);
    memcpy(b + 240, opts, (size_t)on);
    return ip_len;
}

static const char *mname(int t) {
    switch (t) {
    case 1: return "discover";
    case 3: return "request";
    case 4: return "decline";
    case 5: return "ack";
    case 6: return "nak";
    case 7: return "release";
    default: return "?";
    }
}

/* 1 = send out, 0 = ignore. */
static int handle(const struct dhcp_view *v, uint8_t *out, int cap) {
    char ipb[32], macb[24];
    int n = 0;
    if (!v->ok) return 0;
    snprintf(macb, sizeof macb, "%02x:%02x:%02x:%02x:%02x:%02x",
             v->mac[0], v->mac[1], v->mac[2], v->mac[3], v->mac[4], v->mac[5]);
    if (v->msg == 4 || v->msg == 7) {
        free_mac(v->mac);
        fprintf(stderr, "dhcp %s %s\n", mname(v->msg), macb);
        return 0;
    }
    if (v->msg == 1) {
        struct lease *l = alloc_lease(v->mac);
        int bcast = (v->ciaddr == 0) || (v->flags & 0x8000u);
        if (!l) {
            fprintf(stderr, "dhcp discover %s pool full\n", macb);
            return 0;
        }
        n = build_reply(v, 2, l->ip, bcast, out, cap);
        fmt_ip(l->ip, ipb, (int)sizeof ipb);
        fprintf(stderr, "dhcp offer %s %s\n", macb, ipb);
        fflush(stderr);
        return n;
    }
    if (v->msg == 3) {
        uint32_t want = 0;
        struct lease *other;
        int bcast;
        if (v->has_sid && v->sid != router_h) return 0;
        if (v->has_req) want = v->req_ip;
        else if (v->ciaddr) want = v->ciaddr;
        if (!want || !in_pool(want)) {
            n = build_reply(v, 6, 0, 1, out, cap);
            fmt_ip(want, ipb, (int)sizeof ipb);
            fprintf(stderr, "dhcp nak %s %s\n", macb, want ? ipb : "none");
            fflush(stderr);
            return n;
        }
        other = find_ip(want);
        if (other && memcmp(other->mac, v->mac, 6) != 0) {
            n = build_reply(v, 6, 0, 1, out, cap);
            fmt_ip(want, ipb, (int)sizeof ipb);
            fprintf(stderr, "dhcp nak %s %s in use\n", macb, ipb);
            fflush(stderr);
            return n;
        }
        bind_lease(v->mac, want);
        bcast = (v->ciaddr == 0) || (v->flags & 0x8000u);
        n = build_reply(v, 5, want, bcast, out, cap);
        fmt_ip(want, ipb, (int)sizeof ipb);
        fprintf(stderr, "dhcp ack %s %s\n", macb, ipb);
        fflush(stderr);
        return n;
    }
    return 0;
}

static int open_l2(const char *ifname, int *ifindex) {
    int fd, idx;
    struct sockaddr_ll sll;
    idx = (int)if_nametoindex(ifname);
    if (idx <= 0) {
        fprintf(stderr, "titan_dhcp_gw: no iface %s\n", ifname);
        return -1;
    }
    fd = socket(AF_PACKET, SOCK_DGRAM, htons(ETH_P_IP));
    if (fd < 0) {
        perror("titan_dhcp_gw socket");
        return -1;
    }
    memset(&sll, 0, sizeof sll);
    sll.sll_family = AF_PACKET;
    sll.sll_protocol = htons(ETH_P_IP);
    sll.sll_ifindex = idx;
    if (bind(fd, (struct sockaddr *)&sll, sizeof sll) != 0) {
        perror("titan_dhcp_gw bind");
        close(fd);
        return -1;
    }
    *ifindex = idx;
    return fd;
}

static int send_l2(int fd, int ifindex, const uint8_t *pkt, int len) {
    struct sockaddr_ll sll;
    int ihl = (pkt[0] & 0x0f) * 4;
    const uint8_t *b;
    uint32_t dst;
    if (len < ihl + 8 + 44) return -1;
    b = pkt + ihl + 8;
    dst = rd32(pkt + 16);
    memset(&sll, 0, sizeof sll);
    sll.sll_family = AF_PACKET;
    sll.sll_protocol = htons(ETH_P_IP);
    sll.sll_ifindex = ifindex;
    sll.sll_halen = 6;
    if (dst == 0xffffffffu) memset(sll.sll_addr, 0xff, 6);
    else memcpy(sll.sll_addr, b + 28, 6);
    if (sendto(fd, pkt, (size_t)len, 0, (struct sockaddr *)&sll, sizeof sll) < 0) {
        perror("titan_dhcp_gw send");
        return -1;
    }
    return 0;
}

static int serve(const char *ifname) {
    int fd, ifindex = 0;
    uint8_t buf[1600], out[1600];
    char rbuf[32];
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_handler = on_sig;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);
    fd = open_l2(ifname, &ifindex);
    if (fd < 0) return 1;
    load_leases();
    fmt_ip(router_h, rbuf, (int)sizeof rbuf);
    fprintf(stderr, "titan_dhcp_gw up iface=%s router=%s/%d pool=.%u-.%u\n",
            ifname, rbuf, prefix_len, POOL_LO, POOL_HI);
    fflush(stderr);
    while (!stop_flag) {
        struct dhcp_view v;
        ssize_t n = recv(fd, buf, sizeof buf, 0);
        int outn;
        if (n < 0) {
            if (errno == EINTR) continue;
            perror("titan_dhcp_gw recv");
            break;
        }
        if (!parse_dhcp(buf, (int)n, &v)) continue;
        outn = handle(&v, out, (int)sizeof out);
        if (outn > 0) send_l2(fd, ifindex, out, outn);
    }
    close(fd);
    fprintf(stderr, "titan_dhcp_gw stop\n");
    return 0;
}

static int build_discover(uint8_t mac[6], uint32_t xid, int msg, uint32_t req, int with_sid,
                          uint8_t *out, int cap) {
    struct dhcp_view v;
    uint8_t opts[64];
    int on = 0, boot_len, udp_len, ip_len;
    uint8_t *ip, *udp, *b, u8;
    memset(&v, 0, sizeof v);
    v.xid = xid;
    v.flags = 0x8000;
    memcpy(v.mac, mac, 6);
    memcpy(v.chaddr, mac, 6);
    u8 = (uint8_t)msg;
    on = put_opt(opts, on, (int)sizeof opts, 53, &u8, 1);
    if (req) {
        uint8_t raw[4];
        wr32(raw, req);
        on = put_opt(opts, on, (int)sizeof opts, 50, raw, 4);
    }
    if (with_sid) {
        uint8_t raw[4];
        wr32(raw, router_h);
        on = put_opt(opts, on, (int)sizeof opts, 54, raw, 4);
    }
    opts[on++] = 255;
    boot_len = 240 + on;
    if (boot_len < BOOT_MIN) boot_len = BOOT_MIN;
    udp_len = 8 + boot_len;
    ip_len = 20 + udp_len;
    if (ip_len > cap) return -1;
    memset(out, 0, (size_t)ip_len);
    ip = out;
    ip[0] = 0x45;
    wr16(ip + 2, (uint16_t)ip_len);
    ip[8] = 64;
    ip[9] = 17;
    wr32(ip + 12, 0);
    wr32(ip + 16, 0xffffffffu);
    {
        uint16_t c = ip_checksum(ip, 20);
        memcpy(ip + 10, &c, 2);
    }
    udp = ip + 20;
    wr16(udp + 0, 68);
    wr16(udp + 2, 67);
    wr16(udp + 4, (uint16_t)udp_len);
    b = udp + 8;
    b[0] = 1;
    b[1] = 1;
    b[2] = 6;
    wr32(b + 4, xid);
    wr16(b + 10, 0x8000);
    memcpy(b + 28, mac, 6);
    wr32(b + 236, 0x63825363u);
    memcpy(b + 240, opts, (size_t)on);
    return ip_len;
}

static int opt_u32(const uint8_t *boot, int boot_len, uint8_t code, uint32_t *out) {
    int opt = 240;
    if (boot_len < 241) return 0;
    while (opt < boot_len) {
        uint8_t c = boot[opt], n;
        if (c == 255) return 0;
        if (c == 0) { opt++; continue; }
        if (opt + 1 >= boot_len) return 0;
        n = boot[opt + 1];
        if (opt + 2 + n > boot_len) return 0;
        if (c == code && n == 4) {
            *out = rd32(boot + opt + 2);
            return 1;
        }
        opt += 2 + n;
    }
    return 0;
}

static int selftest(void) {
    uint8_t mac[6] = {0x02, 0x00, 0x11, 0x22, 0x33, 0x44};
    uint8_t req[1600], rep[1600];
    struct dhcp_view v;
    int n, rn, ihl;
    uint32_t yi, sid, gw, again;
    uint16_t sum;
    const uint8_t *boot;
    char path[] = "/tmp/titan_dhcp_gw.selftest.leases";

    lease_path = path;
    unlink(path);
    memset(leases, 0, sizeof leases);
    if (parse_router("192.168.6.1/24") != 0) return 2;

    n = build_discover(mac, 0x204dba8d, 1, 0, 0, req, (int)sizeof req);
    if (n < 0 || !parse_dhcp(req, n, &v) || v.msg != 1) {
        fprintf(stderr, "selftest: parse discover failed\n");
        return 3;
    }
    rn = handle(&v, rep, (int)sizeof rep);
    if (rn < 40) {
        fprintf(stderr, "selftest: no offer\n");
        return 4;
    }
    sum = ip_checksum(rep, 20);
    if (sum != 0) {
        fprintf(stderr, "selftest: ip checksum %04x\n", sum);
        return 5;
    }
    ihl = 20;
    if (rd16(rep + ihl) != 67 || rd16(rep + ihl + 2) != 68) {
        fprintf(stderr, "selftest: udp ports\n");
        return 6;
    }
    boot = rep + ihl + 8;
    yi = rd32(boot + 16);
    if (!in_pool(yi)) {
        fprintf(stderr, "selftest: yiaddr outside pool\n");
        return 7;
    }
    if (!opt_u32(boot, rn - ihl - 8, 54, &sid) || sid != router_h) return 8;
    if (!opt_u32(boot, rn - ihl - 8, 3, &gw) || gw != router_h) return 9;
    if (boot[240] != 53 || boot[242] != 2) return 10;

    rn = handle(&v, rep, (int)sizeof rep);
    again = rd32(rep + 20 + 8 + 16);
    if (again != yi) {
        fprintf(stderr, "selftest: lease not stable\n");
        return 11;
    }

    n = build_discover(mac, 0x10, 3, 0x0a4c02abu, 0, req, (int)sizeof req); /* 10.76.2.171 */
    if (!parse_dhcp(req, n, &v)) return 12;
    rn = handle(&v, rep, (int)sizeof rep);
    boot = rep + 28;
    if (rn < 40 || boot[240] != 53 || boot[242] != 6) {
        fprintf(stderr, "selftest: expected nak for foreign prefix\n");
        return 13;
    }

    n = build_discover(mac, 0x11, 3, yi, 1, req, (int)sizeof req);
    if (!parse_dhcp(req, n, &v)) return 14;
    rn = handle(&v, rep, (int)sizeof rep);
    boot = rep + 28;
    if (boot[242] != 5 || rd32(boot + 16) != yi) {
        fprintf(stderr, "selftest: expected ack\n");
        return 15;
    }
    unlink(path);
    printf("selftest ok yiaddr=%u.%u.%u.%u\n",
           (yi >> 24) & 255, (yi >> 16) & 255, (yi >> 8) & 255, yi & 255);
    return 0;
}

static int probe(const char *ifname) {
    int fd, ifindex = 0, i;
    uint8_t mac[6] = {0x02, 0x00, 0x11, 0x22, 0x33, 0x44};
    uint8_t pkt[1600], buf[1600];
    int n = build_discover(mac, 0xA11A5001u, 1, 0, 0, pkt, (int)sizeof pkt);
    struct timeval tv;
    if (n < 0) return 2;
    fd = open_l2(ifname, &ifindex);
    if (fd < 0) return 1;
    tv.tv_sec = 2;
    tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    if (send_l2(fd, ifindex, pkt, n) != 0) {
        close(fd);
        return 3;
    }
    for (i = 0; i < 20; i++) {
        struct dhcp_view v;
        ssize_t rn = recv(fd, buf, sizeof buf, 0);
        int ihl;
        const uint8_t *b;
        uint32_t yi, gw = 0, sid = 0;
        if (rn < 0) break;
        if (!parse_dhcp(buf, (int)rn, &v)) {
            /* parse_dhcp only accepts requests. Look at replies directly. */
        }
        if (rn < 20 + 8 + 244) continue;
        ihl = (buf[0] & 0x0f) * 4;
        if (rd16(buf + ihl + 2) != 68) continue;
        b = buf + ihl + 8;
        if (b[0] != 2) continue;
        if (rd32(b + 4) != 0xA11A5001u) continue;
        yi = rd32(b + 16);
        opt_u32(b, (int)rn - ihl - 8, 3, &gw);
        opt_u32(b, (int)rn - ihl - 8, 54, &sid);
        printf("probe offer %u.%u.%u.%u router %u.%u.%u.%u server %u.%u.%u.%u\n",
               (yi >> 24) & 255, (yi >> 16) & 255, (yi >> 8) & 255, yi & 255,
               (gw >> 24) & 255, (gw >> 16) & 255, (gw >> 8) & 255, gw & 255,
               (sid >> 24) & 255, (sid >> 16) & 255, (sid >> 8) & 255, sid & 255);
        close(fd);
        if ((yi & 0xffffff00u) == 0xc0a80600u && gw == 0xc0a80601u && sid == 0xc0a80601u)
            return 0;
        return 4;
    }
    fprintf(stderr, "probe: no offer\n");
    close(fd);
    return 5;
}

int main(int argc, char **argv) {
    const char *ifname;
    setvbuf(stderr, NULL, _IOLBF, 0);
    if (argc >= 2 && strcmp(argv[1], "--selftest") == 0) return selftest();
    if (argc >= 3 && strcmp(argv[1], "--probe") == 0) return probe(argv[2]);
    if (argc < 2) {
        fprintf(stderr, "usage: titan_dhcp_gw <ifname> [router/prefix]\n");
        return 2;
    }
    ifname = argv[1];
    if (argc >= 3) {
        if (parse_router(argv[2]) != 0) {
            fprintf(stderr, "titan_dhcp_gw: bad router %s\n", argv[2]);
            return 2;
        }
    } else if (pick_router(ifname) != 0) {
        if (parse_router("192.168.6.1/24") != 0) return 2;
        fprintf(stderr, "titan_dhcp_gw: no .1 on %s, using 192.168.6.1/24\n", ifname);
    }
    return serve(ifname);
}
