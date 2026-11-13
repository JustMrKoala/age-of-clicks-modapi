#!/usr/bin/env python3
"""Opens (or closes) the game's ports on the home router through UPnP, so players on the
internet can reach this server. Needs the router to allow it (FRITZ!Box: "Zelfstandig
portvrijgaven" / "Selbstständige Portfreigaben" for this device). No extra packages needed.

    python3 upnp-ports.py            show the router, the public address and current mappings
    python3 upnp-ports.py open       forward TCP 54555 and UDP 54777 to this machine
    python3 upnp-ports.py close      remove those forwards
    python3 upnp-ports.py close-legacy   remove the single-server ports 54555/54777

With lobby-service.properties present, the lobby server's ports are used instead: its API port
(TCP) and every match port (TCP and UDP).
"""
import re
import socket
import sys
import urllib.request
from urllib.parse import urljoin

LEGACY = [("TCP", 54555), ("UDP", 54777)]


def configured_ports():
    try:
        cfg = {}
        for line in open("lobby-service.properties", encoding="utf-8"):
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip()
    except OSError:
        return LEGACY
    first, size = int(cfg.get("pool_first_port", 54601)), int(cfg.get("pool_size", 2))
    ports = [("TCP", int(cfg.get("port", 54600)))]
    for p in range(first, first + size):
        ports += [("TCP", p), ("UDP", p)]
    return ports


PORTS = configured_ports()
DESCRIPTION = "Age of Clicks server"
SERVICES = ["urn:schemas-upnp-org:service:WANIPConnection:1",
            "urn:schemas-upnp-org:service:WANIPConnection:2",
            "urn:schemas-upnp-org:service:WANPPPConnection:1"]


def discover():
    msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
           "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n").encode()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    s.settimeout(3)
    s.sendto(msg, ("239.255.255.250", 1900))
    locations = set()
    try:
        while True:
            data = s.recv(4096).decode(errors="replace")
            m = re.search(r"(?im)^location:\s*(\S+)", data)
            if m:
                locations.add(m.group(1))
    except socket.timeout:
        pass
    for loc in locations:
        xml = urllib.request.urlopen(loc, timeout=5).read().decode(errors="replace")
        for service in SERVICES:
            block = re.search(r"<service>(?:(?!</service>).)*" + re.escape(service) + r"(?:(?!</service>).)*</service>", xml, re.S)
            if block:
                control = re.search(r"<controlURL>([^<]+)</controlURL>", block.group(0)).group(1)
                return urljoin(loc, control), service
    raise SystemExit("No UPnP router found. Is 'Zelfstandig portvrijgaven' allowed for this device?")


def soap(url, service, action, args):
    body = "".join(f"<{k}>{v}</{k}>" for k, v in args.items())
    envelope = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
                's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
                f'<u:{action} xmlns:u="{service}">{body}</u:{action}></s:Body></s:Envelope>')
    req = urllib.request.Request(url, envelope.encode(), {
        "Content-Type": 'text/xml; charset="utf-8"', "SOAPAction": f'"{service}#{action}"'})
    try:
        return urllib.request.urlopen(req, timeout=5).read().decode(errors="replace")
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        code = re.search(r"<errorCode>(\d+)</errorCode>", detail)
        text = re.search(r"<errorDescription>([^<]*)</errorDescription>", detail)
        raise RuntimeError(f"{action} refused: {code.group(1) if code else e.code} {text.group(1) if text else ''}")


def local_ip_towards(host):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect((host, 80))
    return s.getsockname()[0]


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "show"
    url, service = discover()
    router = re.search(r"//([^:/]+)", url).group(1)
    me = local_ip_towards(router)
    ext = re.search(r"<NewExternalIPAddress>([^<]*)<", soap(url, service, "GetExternalIPAddress", {}))
    print(f"router: {router}  this machine: {me}  router's internet address: {ext.group(1) if ext else '?'}")
    if mode == "close-legacy":
        mode = "close"
        ports = LEGACY
    else:
        ports = PORTS
    for proto, port in ports:
        if mode == "open":
            soap(url, service, "AddPortMapping", {
                "NewRemoteHost": "", "NewExternalPort": port, "NewProtocol": proto, "NewInternalPort": port,
                "NewInternalClient": me, "NewEnabled": 1, "NewPortMappingDescription": DESCRIPTION,
                "NewLeaseDuration": 0})
            print(f"  opened {proto} {port} -> {me}:{port}")
        elif mode == "close":
            try:
                soap(url, service, "DeletePortMapping", {"NewRemoteHost": "", "NewExternalPort": port, "NewProtocol": proto})
                print(f"  closed {proto} {port}")
            except RuntimeError as e:
                print(f"  {proto} {port}: {e}")
        else:
            try:
                r = soap(url, service, "GetSpecificPortMappingEntry", {"NewRemoteHost": "", "NewExternalPort": port, "NewProtocol": proto})
                client = re.search(r"<NewInternalClient>([^<]*)<", r)
                print(f"  {proto} {port}: forwarded to {client.group(1) if client else '?'}")
            except RuntimeError:
                print(f"  {proto} {port}: not forwarded")


if __name__ == "__main__":
    main()
