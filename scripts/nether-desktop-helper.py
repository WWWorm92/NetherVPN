#!/usr/bin/python3
"""Single privileged desktop transaction. Installed root-owned, never run from GUI source tree.

pkexec authenticates ONCE for connect and ONCE for disconnect, rather than
for each install/systemctl action. No shell, command substitution, or key argv.
"""
import configparser
import ipaddress
import os
import pwd
import re
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

SERVICE = "nethervpn-client.service"
ROOT = Path("/etc/nethervpn")
MAX_PROFILE = 65536
MAX_SETTINGS = 4096


def fail(message):
    raise RuntimeError(message)


def source_directory(uid):
    user = pwd.getpwuid(uid)
    # Walk the fixed path without following symlinks.
    fd = os.open(user.pw_dir, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)
    try:
        for name in (".config", "nethervpn", "profiles"):
            nxt = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=fd)
            os.close(fd)
            fd = nxt
        info = os.fstat(fd)
        if info.st_uid != uid or not stat.S_ISDIR(info.st_mode):
            fail("Каталог профилей должен принадлежать текущему пользователю")
        if info.st_mode & 0o077:
            fail("Каталог профилей должен иметь права 0700")
        return fd
    except BaseException:
        os.close(fd)
        raise


def safe_profile_name(name):
    if not re.fullmatch(r"[A-Za-z0-9_. -]{1,100}\.conf", name) or name.startswith(".") or ".." in name:
        fail("Недопустимое имя файла профиля")
    return name


def read_user_file(directory, name, uid, maximum):
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=directory)
    try:
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != uid or
                info.st_mode & 0o077 or info.st_size > maximum):
            fail("Небезопасные права/владелец файла: " + name)
        chunks = []
        size = 0
        while True:
            part = os.read(fd, 8192)
            if not part:
                break
            size += len(part)
            if size > maximum:
                fail("Слишком большой файл: " + name)
            chunks.append(part)
        return b"".join(chunks)
    finally:
        os.close(fd)


def validate(profile, settings):
    identity = configparser.ConfigParser(interpolation=None, strict=True)
    routing = configparser.ConfigParser(interpolation=None, strict=True)
    identity.read_string(profile.decode("utf-8"))
    routing.read_string(settings.decode("utf-8"))
    for section, key in (("Interface", "PrivateKey"), ("Interface", "Address"),
                         ("Peer", "PublicKey"), ("Peer", "Endpoint"), ("Peer", "AllowedIPs")):
        if not identity.has_option(section, key):
            fail("Отсутствует обязательный параметр %s.%s" % (section, key))
    for section, key in (("Interface", "PrivateKey"), ("Peer", "PublicKey")):
        if not re.fullmatch(r"[0-9a-fA-F]{64}", identity.get(section, key)):
            fail("Некорректный X25519 ключ в конфигурации")
    addr = ipaddress.ip_interface(identity.get("Interface", "Address"))
    if addr.version != 4:
        fail("Поддерживается только IPv4 клиента")
    ipaddress.ip_address(identity.get("Peer", "AllowedIPs"))
    mode = routing.get("Client", "Mode")
    if mode not in ("full", "split"):
        fail("Режим должен быть full или split")
    dns = ipaddress.IPv4Address(routing.get("Client", "DNS"))
    if dns.is_unspecified or dns.is_multicast:
        fail("Недопустимый DNS")
    if routing.get("Client", "AutoConnect") not in ("true", "false"):
        fail("AutoConnect должен быть true или false")
    endpoint = identity.get("Peer", "Endpoint")
    host, sep, port = endpoint.rpartition(":")
    if not sep or not port.isdecimal() or not 0 < int(port) < 65536:
        fail("Неверный Endpoint")
    if mode == "full":
        ip = ipaddress.IPv4Address(host)
        if ip.is_unspecified or ip.is_loopback:
            fail("Full Tunnel требует числовой IPv4 Endpoint")


def root_directory():
    st = os.lstat(ROOT)
    if not stat.S_ISDIR(st.st_mode) or st.st_uid != 0 or (st.st_mode & 0o022):
        fail("/etc/nethervpn должен быть корневым каталогом без прав записи посторонним")


def replace_root_file(name, content):
    fd, tmp = tempfile.mkstemp(prefix=".desktop-", dir=str(ROOT))
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as file:
            file.write(content)
            file.flush()
            os.fsync(file.fileno())
        os.replace(tmp, ROOT / name)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def call(*args):
    result = subprocess.run(args, text=True, capture_output=True, timeout=40, check=False)
    if result.returncode:
        raise RuntimeError((result.stderr or result.stdout or str(args)).strip())


def connect(name, uid):
    safe_profile_name(name)
    source = source_directory(uid)
    try:
        profile = read_user_file(source, name, uid, MAX_PROFILE)
        settings = read_user_file(source, name[:-5] + ".prefs", uid, MAX_SETTINGS)
    finally:
        os.close(source)
    validate(profile, settings)
    if (ROOT / "server.conf").is_file():
        result = subprocess.run(
            ["/usr/bin/systemctl", "is-active", "--quiet", "nethervpn-server.service"],
            capture_output=True, check=False)
        if result.returncode == 0:
            local_settings = configparser.ConfigParser(interpolation=None)
            local_settings.read_string(settings.decode("utf-8"))
            if local_settings.get("Client", "Mode") == "full":
                fail("Full Tunnel нельзя включать на работающем VPN-сервере: будет нарушена маршрутизация шлюза.")
            gateway = configparser.ConfigParser(interpolation=None)
            client = configparser.ConfigParser(interpolation=None)
            gateway.read(ROOT / "server.conf")
            client.read_string(profile.decode("utf-8"))
            if gateway.get("Interface", "Name", fallback="nvpn0") == client.get(
                    "Interface", "Name", fallback="nvpn0"):
                fail("Сервер уже использует этот TUN. Нужен другой интерфейс или хост.")
    root_directory()
    old = {}
    for filename in ("client.conf", "client-settings.conf"):
        path = ROOT / filename
        if path.is_symlink():
            fail("Символическая ссылка /etc/nethervpn/" + filename + " недопустима")
        old[filename] = path.read_bytes() if path.exists() else None
    cfg = configparser.ConfigParser(interpolation=None)
    cfg.read_string(settings.decode("utf-8"))
    autoconnect = cfg.getboolean("Client", "AutoConnect")
    try:
        replace_root_file("client.conf", profile)
        replace_root_file("client-settings.conf", settings)
        call("/usr/bin/systemctl", "restart", SERVICE)
        call("/usr/bin/systemctl", "enable" if autoconnect else "disable", SERVICE)
    except Exception:
        # Restore prior credentials and policy if activation fails.
        for filename, previous in old.items():
            if previous is None:
                (ROOT / filename).unlink(missing_ok=True)
            else:
                replace_root_file(filename, previous)
        try:
            call("/usr/bin/systemctl", "stop", SERVICE)
        except Exception:
            pass
        raise
    print("NetherVPN: профиль и настройки применены; служба запущена.")


def disconnect():
    call("/usr/bin/systemctl", "stop", SERVICE)
    print("NetherVPN: подключение остановлено.")


def main(argv):
    if os.geteuid() != 0:
        fail("Запускайте через pkexec")
    action = argv[1] if len(argv) >= 2 else ""
    if action == "disconnect" and len(argv) == 2:
        disconnect()
        return
    if action != "connect" or len(argv) != 3:
        fail("Использование: nether-desktop-helper connect PROFILE.conf | disconnect")
    uid_str = os.environ.get("PKEXEC_UID", "")
    if not uid_str.isdecimal() or int(uid_str) < 1:
        fail("Не определён UID пользователя, вызвавшего pkexec")
    connect(argv[2], int(uid_str))


if __name__ == "__main__":
    try:
        main(sys.argv)
    except Exception as err:
        print("NetherVPN: " + str(err), file=sys.stderr)
        sys.exit(1)
