"""Host-only NetherVPN connection preferences; no secret keys or server config."""
import configparser
import ipaddress


def validate_dns(raw: str) -> str:
    value = str(ipaddress.IPv4Address(raw.strip()))
    if value == "0.0.0.0" or ipaddress.IPv4Address(value).is_multicast:
        raise ValueError("Недопустимый IPv4 DNS")
    return value


def encode(mode: str, dns: str, autoconnect: bool) -> str:
    if mode not in ("full", "split"):
        raise ValueError("Режим должен быть full или split")
    resolver = validate_dns(dns)
    auto = "true" if autoconnect else "false"
    return f"[Client]\nMode = {mode}\nDNS = {resolver}\nAutoConnect = {auto}\n"


def decode(text: str, legacy_mode="split", legacy_dns="1.1.1.1"):
    parser = configparser.ConfigParser(interpolation=None, strict=True)
    if text.strip():
        parser.read_string(text)
    mode = parser.get("Client", "Mode", fallback=legacy_mode)
    if mode not in ("full", "split"):
        raise ValueError("Недопустимый режим в локальных настройках")
    resolver = validate_dns(parser.get("Client", "DNS", fallback=legacy_dns))
    auto = parser.getboolean("Client", "AutoConnect", fallback=False)
    return mode, resolver, auto
