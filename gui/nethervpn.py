#!/usr/bin/env python3
"""NetherVPN Linux desktop client, backed by a privileged systemd service."""
import configparser
from client_preferences import encode, decode
import threading
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

BASE = Path.home() / ".config" / "nethervpn" / "profiles"
APP_ROOT = Path(__file__).resolve().parent.parent
CLIENT = APP_ROOT / "bin" / "nether-client"
HELPER = "/usr/local/libexec/nether-desktop-helper"
BG = "#0a121f"
PANEL = "#142233"
TEXT = "#f1f7ff"
MUTED = "#9db3c9"
ACCENT = "#3ad9b8"
RAISED = "#1d3045"
AMBER = "#ffc067"
ERROR = "#ff7282"

def profile_meta(path):
    cfg = configparser.ConfigParser(interpolation=None)
    cfg.read(path)
    return (cfg.get("Peer", "Endpoint", fallback="—"),
            cfg.get("Interface", "Name", fallback="nvpn0"),
            cfg.getboolean("Interface", "FullTunnel", fallback=False),
            cfg.get("Interface", "DNS", fallback="1.1.1.1"))

class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("NetherVPN")
        self.geometry("410x690")
        self.minsize(360, 580)
        self.configure(bg=BG)
        BASE.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(BASE, 0o700)
        self.process = None
        self.busy = False
        self.logfile = None
        self.log_offset = 0
        self.active = None
        self.since = None
        self.authenticated = False
        self.previous_counters = None
        self.last_service_poll = 0.0
        self.service_running = False
        self.items = []
        self.selected_name = None
        self.log_history = []
        self.logbox = None
        self.profile_list = None
        self.mode_buttons = {}
        self.settings_window = None
        self.profile_window = None
        self.profile_title = tk.StringVar(value="Выбрать сервер")
        self.state = tk.StringVar(value="Не подключено")
        self.endpoint = tk.StringVar(value="Выберите профиль")
        self.mode_text = tk.StringVar(value="Выберите профиль")
        self.mode_var = tk.StringVar(value="split")
        self.dns_var = tk.StringVar(value="1.1.1.1")
        self.autostart_var = tk.BooleanVar(value=False)
        self.timing = tk.StringVar(value="—")
        self.tx = tk.StringVar(value="—")
        self.rx = tk.StringVar(value="—")
        self.speed = tk.StringVar(value="↓ — КиБ/с  ↑ — КиБ/с")
        self.status_tag = tk.StringVar(value="●  НЕ ПОДКЛЮЧЕНО")
        self.status_hint = tk.StringVar(value="Импортируйте профиль, чтобы начать")
        self._ui()
        self.refresh()
        self.after(500, self.tick)
        self.protocol("WM_DELETE_WINDOW", self.close_app)

    def label(self, parent, text, size=11, fg=TEXT, weight="normal"):
        return tk.Label(parent, text=text, bg=parent.cget("bg"), fg=fg,
                        font=("DejaVu Sans", size, weight), anchor="w",
                        justify="left")

    def button(self, parent, text, command, primary=False):
        return tk.Button(parent, text=text, command=command,
                         font=("DejaVu Sans", 10, "bold"),
                         bg=ACCENT if primary else RAISED,
                         fg=BG if primary else TEXT,
                         activebackground="#68e8cb" if primary else "#2a4560",
                         activeforeground=BG if primary else TEXT,
                         relief="flat", bd=0, padx=16, pady=11,
                         cursor="hand2", highlightthickness=0)

    def _ui(self):
        """Single-screen compact client; advanced controls live in dialogs."""
        top = tk.Frame(self, bg=BG)
        top.pack(fill="x", padx=22, pady=(19, 9))
        self.label(top, "◈  NetherVPN", 17, TEXT, "bold").pack(side="left")
        self.label(top, "LINUX  ·  BETA", 9, MUTED, "bold").pack(
            side="right", pady=(7, 0))

        center = tk.Frame(self, bg=BG)
        center.pack(fill="both", expand=True, padx=15)
        # A canvas ring keeps the same composition on small Linux desktops
        # without embedding unmaintainable screenshots or external fonts.
        self.connect_btn = tk.Canvas(center, width=246, height=246, bg=BG,
                                     bd=0, highlightthickness=0,
                                     cursor="hand2", takefocus=1)
        self.connect_btn.pack(expand=True, pady=(10, 5))
        self.outer_ring = self.connect_btn.create_oval(
            12, 12, 234, 234, outline=RAISED, width=4)
        self.inner_ring = self.connect_btn.create_oval(
            18, 18, 228, 228, outline="#384354", width=1)
        self.power_text = self.connect_btn.create_text(
            123, 118, text="Подключить", fill=TEXT,
            font=("DejaVu Sans", 16, "bold"), anchor="center")
        self.power_subtext = self.connect_btn.create_text(
            123, 146, text="Нажмите, чтобы включить",
            fill=MUTED, font=("DejaVu Sans", 9))
        self.connect_btn.bind("<Button-1>", lambda _e: self.toggle_connection())
        self.connect_btn.bind("<Return>", lambda _e: self.toggle_connection())
        self.connect_btn.bind("<space>", lambda _e: self.toggle_connection())

        self.status_badge = tk.Label(
            center, textvariable=self.status_tag, bg=BG, fg=MUTED,
            font=("DejaVu Sans", 10, "bold"))
        self.status_badge.pack(pady=(0, 4))
        tk.Label(center, textvariable=self.status_hint, bg=BG, fg=MUTED,
                 font=("DejaVu Sans", 9)).pack(pady=(0, 6))

        lower = tk.Frame(self, bg=BG)
        lower.pack(fill="x", padx=18, pady=(0, 10))
        # Route policy is a one-tap shortcut, not a screen full of settings.
        self.mode_row = tk.Button(
            lower, textvariable=self.mode_text, bg=BG, fg=MUTED,
            activebackground=BG, activeforeground=TEXT,
            font=("DejaVu Sans", 10), bd=0, relief="flat",
            cursor="hand2", pady=11, command=self.open_settings)
        self.mode_row.pack(fill="x", pady=(1, 8))
        selected = tk.Frame(lower, bg=PANEL, padx=15, pady=13)
        selected.pack(fill="x")
        tk.Label(selected, text="СЕРВЕР", bg=PANEL, fg=MUTED,
                 font=("DejaVu Sans", 9, "bold")).pack(anchor="w")
        self.profile_btn = tk.Button(
            selected, textvariable=self.profile_title,
            bg=PANEL, fg=TEXT, activebackground=PANEL,
            activeforeground=ACCENT, font=("DejaVu Sans", 15, "bold"),
            relief="flat", bd=0, cursor="hand2", anchor="w",
            command=self.open_profiles)
        self.profile_btn.pack(fill="x", pady=(4, 1))
        tk.Label(selected, textvariable=self.endpoint, bg=PANEL, fg=MUTED,
                 font=("DejaVu Sans", 9), anchor="w").pack(fill="x")

        nav = tk.Frame(self, bg=PANEL, pady=11)
        nav.pack(fill="x")
        self.button(nav, "⌂  Главная", lambda: None, True).pack(
            side="left", expand=True)
        self.button(nav, "⚙  Настройки", self.open_settings).pack(
            side="left", expand=True)
        self.button(nav, "＋  Импорт", self.import_profile).pack(
            side="left", expand=True)
        self.log("Приложение готово. Настройки доступны отдельно.")

    def toggle_connection(self):
        if self.busy:
            return
        if self.service_is_running():
            self.disconnect()
        else:
            self.connect()

    def open_settings(self):
        if self.settings_window is not None and self.settings_window.winfo_exists():
            self.settings_window.lift()
            return
        win = tk.Toplevel(self)
        self.settings_window = win
        win.title("NetherVPN · Настройки")
        win.geometry("390x540")
        win.minsize(340, 500)
        win.configure(bg=BG)
        win.transient(self)
        container = tk.Frame(win, bg=BG, padx=23, pady=20)
        container.pack(fill="both", expand=True)
        self.label(container, "Настройки подключения", 18, TEXT, "bold").pack(
            anchor="w", pady=(0, 17))
        self.label(container, "МАРШРУТИЗАЦИЯ", 10, MUTED, "bold").pack(anchor="w")
        self.mode_buttons = {}
        for value, caption in (("full", "Весь IPv4 через VPN"),
                               ("split", "Только адрес VPN-сервера")):
            radio = tk.Radiobutton(
                container, text=caption, variable=self.mode_var, value=value,
                bg=BG, fg=TEXT, selectcolor=RAISED,
                activebackground=BG, activeforeground=TEXT,
                font=("DejaVu Sans", 11), command=self.refresh_mode_label)
            radio.pack(fill="x", anchor="w", pady=(10, 0))
            self.mode_buttons[value] = radio
        self.label(container, "DNS для полного туннеля", 10, MUTED).pack(
            anchor="w", pady=(18, 6))
        tk.Entry(container, textvariable=self.dns_var, bg=RAISED,
                 fg=TEXT, insertbackground=TEXT,
                 relief="flat", font=("DejaVu Sans Mono", 11)).pack(
                     fill="x", ipady=9)
        tk.Checkbutton(
            container, text="Подключать при запуске Linux",
            variable=self.autostart_var, bg=BG, fg=TEXT,
            selectcolor=RAISED, activebackground=BG,
            activeforeground=TEXT, font=("DejaVu Sans", 10)).pack(
                anchor="w", pady=(16, 13))
        self.button(container, "Сохранить настройки",
                    lambda: self.save_settings()).pack(fill="x")
        self.button(container, "Статистика и диагностика",
                    self.open_diagnostics).pack(fill="x", pady=(12, 0))
        self.button(container, "Журнал событий",
                    self.open_logs).pack(fill="x", pady=(7, 0))
        self.label(container, "IPv6 в Full Tunnel пока блокируется.",
                   9, MUTED).pack(anchor="w", pady=(13, 0))
        def close():
            self.mode_buttons = {}
            self.settings_window = None
            win.destroy()
        win.protocol("WM_DELETE_WINDOW", close)
        self.refresh_mode_buttons()

    def open_profiles(self):
        if self.profile_window is not None and self.profile_window.winfo_exists():
            self.profile_window.lift()
            return
        win = tk.Toplevel(self)
        self.profile_window = win
        win.title("NetherVPN · Серверы")
        win.geometry("370x430")
        win.minsize(330, 350)
        win.configure(bg=BG)
        win.transient(self)
        frame = tk.Frame(win, bg=BG, padx=20, pady=18)
        frame.pack(fill="both", expand=True)
        self.label(frame, "Выберите сервер", 18, TEXT, "bold").pack(
            anchor="w", pady=(0, 14))
        self.profile_list = tk.Listbox(
            frame, bg=PANEL, fg=TEXT, font=("DejaVu Sans", 12),
            relief="flat", bd=0, highlightthickness=0,
            selectbackground="#245d62", selectforeground=TEXT,
            exportselection=False)
        self.profile_list.pack(fill="both", expand=True)
        self.refresh()
        def choose(_event=None):
            selected = self.profile_list.curselection()
            if selected and selected[0] < len(self.items):
                self.selected_name = self.items[selected[0]].name
                self.on_select()
                close()
        self.profile_list.bind("<Double-Button-1>", choose)
        self.button(frame, "Выбрать", choose, True).pack(fill="x", pady=(13, 6))
        actions = tk.Frame(frame, bg=BG)
        actions.pack(fill="x")
        self.button(actions, "＋ Импорт", self.import_profile).pack(
            side="left", expand=True)
        self.button(actions, "Удалить", self.delete).pack(
            side="left", expand=True)
        def close():
            self.profile_list = None
            self.profile_window = None
            win.destroy()
        win.protocol("WM_DELETE_WINDOW", close)

    def open_diagnostics(self):
        self.inspect_connection()
        win = tk.Toplevel(self)
        win.title("NetherVPN · Статистика")
        win.geometry("390x320")
        win.configure(bg=BG)
        win.transient(self)
        body = tk.Frame(win, bg=BG, padx=24, pady=22)
        body.pack(fill="both", expand=True)
        self.label(body, "Статистика соединения", 18, TEXT, "bold").pack(
            anchor="w", pady=(0, 16))
        for title, value in (("ОТПРАВЛЕНО", self.tx),
                             ("ПОЛУЧЕНО", self.rx),
                             ("ВРЕМЯ", self.timing),
                             ("СКОРОСТЬ", self.speed)):
            row = tk.Frame(body, bg=BG)
            row.pack(fill="x", pady=7)
            self.label(row, title, 9, MUTED).pack(side="left")
            tk.Label(row, textvariable=value, bg=BG, fg=ACCENT,
                     font=("DejaVu Sans", 11, "bold")).pack(side="right")
        self.button(body, "Посмотреть журнал", self.open_logs).pack(
            fill="x", pady=(12, 0))

    def open_logs(self):
        win = tk.Toplevel(self)
        win.title("NetherVPN · Журнал")
        win.geometry("540x360")
        win.configure(bg=BG)
        win.transient(self)
        self.logbox = tk.Text(
            win, bg=PANEL, fg=TEXT, insertbackground=TEXT,
            font=("DejaVu Sans Mono", 9), wrap="word",
            state="normal", relief="flat", bd=0, padx=12, pady=12)
        self.logbox.pack(fill="both", expand=True, padx=14, pady=14)
        self.logbox.insert("end", "".join(self.log_history))
        self.logbox.see("end")
        self.logbox.configure(state="disabled")
        def close():
            self.logbox = None
            win.destroy()
        win.protocol("WM_DELETE_WINDOW", close)

    def refresh_mode_buttons(self):
        for value, radio in list(self.mode_buttons.items()):
            if radio.winfo_exists():
                radio.configure(fg=ACCENT if self.mode_var.get() == value
                                else TEXT)

    def log(self, msg):
        line = time.strftime("%H:%M:%S") + "  " + msg.rstrip() + "\n"
        self.log_history.append(line)
        # Bound memory without discarding useful recent diagnostics.
        if len(self.log_history) > 250:
            self.log_history = self.log_history[-200:]
        if self.logbox is not None and self.logbox.winfo_exists():
            self.logbox.configure(state="normal")
            self.logbox.insert("end", line)
            self.logbox.see("end")
            self.logbox.configure(state="disabled")

    def refresh(self):
        previous = self.selected_name
        self.items = sorted(p for p in BASE.glob("*.conf")
                            if p.is_file() and not p.is_symlink())
        if previous not in {p.name for p in self.items}:
            self.selected_name = self.items[0].name if self.items else None
        if self.profile_list is not None and self.profile_list.winfo_exists():
            self.profile_list.delete(0, "end")
            for idx, item in enumerate(self.items):
                self.profile_list.insert("end", item.stem)
                if item.name == self.selected_name:
                    self.profile_list.selection_set(idx)
        self.on_select()

    def selected(self):
        if self.selected_name:
            for item in self.items:
                if item.name == self.selected_name:
                    return item
        return None

    @staticmethod
    def preferences_path(path):
        return BASE / (path.stem + ".prefs")

    def load_choices(self, path):
        _, _, legacy_full, legacy_dns = profile_meta(path)
        pref = self.preferences_path(path)
        if pref.is_symlink():
            raise ValueError("Символическая ссылка настроек запрещена")
        content = pref.read_text(encoding="utf-8") if pref.exists() else ""
        return decode(content, legacy_mode="full" if legacy_full else "split",
                      legacy_dns=legacy_dns)

    def refresh_mode_label(self):
        if self.mode_var.get() == "full":
            self.mode_text.set("Весь IPv4 через VPN   ⌄")
        else:
            self.mode_text.set("Раздельный туннель   ⌄")
        self.refresh_mode_buttons()

    def on_select(self, _event=None):
        path = self.selected()
        if path:
            try:
                peer, _, _, _ = profile_meta(path)
                mode, dns, auto = self.load_choices(path)
                self.endpoint.set(peer)
                self.profile_title.set(path.stem + "   ⌄")
                self.mode_var.set(mode)
                self.dns_var.set(dns)
                self.autostart_var.set(auto)
                self.refresh_mode_label()
            except (OSError, ValueError, configparser.Error) as exc:
                self.endpoint.set("Ошибка конфигурации")
                self.profile_title.set(path.stem + "   ⌄")
                self.log(str(exc))
        else:
            self.profile_title.set("Добавить сервер   ＋")
            self.endpoint.set("Профиль ещё не загружен")
            self.refresh_mode_label()

    def save_settings(self, notify=True):
        profile = self.selected()
        if not profile:
            if notify:
                messagebox.showinfo("NetherVPN", "Сначала выберите профиль")
            return None
        try:
            mode = self.mode_var.get()
            dns = self.dns_var.get().strip()
            text = encode(mode, dns, self.autostart_var.get())
            pref = self.preferences_path(profile)
            if pref.is_symlink():
                raise ValueError("Нельзя перезаписать символическую ссылку")
            fd, tmp = tempfile.mkstemp(prefix=".prefs-", dir=str(BASE))
            try:
                os.fchmod(fd, 0o600)
                with os.fdopen(fd, "w", encoding="utf-8") as f:
                    f.write(text)
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(tmp, pref)
            finally:
                if os.path.exists(tmp):
                    os.unlink(tmp)
            self.refresh_mode_label()
            if notify:
                self.log("Настройки сохранены: " + mode + "; DNS " + dns)
                messagebox.showinfo("NetherVPN", "Настройки сохраняются отдельно от .conf.\\n"
                                    "Чтобы применить их, переподключите VPN.")
            return pref
        except (OSError, ValueError) as exc:
            messagebox.showerror("Настройки", str(exc))
            return None

    def import_profile(self):
        filename = filedialog.askopenfilename(filetypes=[("NetherVPN конфигурация", "*.conf")])
        if not filename:
            return
        source = Path(filename)
        dest = BASE / source.name
        if source.resolve() == dest.resolve():
            return
        if dest.exists() and not messagebox.askyesno("Заменить", "Заменить профиль " + dest.name + "?"):
            return
        if dest.is_symlink():
            messagebox.showerror("Импорт", "Нельзя перезаписать символическую ссылку")
            return
        try:
            content = source.read_bytes()
            if len(content) > 65536:
                raise ValueError("Файл слишком большой")
            fd = os.open(dest, os.O_CREAT | os.O_WRONLY | os.O_TRUNC | getattr(os, "O_NOFOLLOW", 0), 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(content)
            os.chmod(dest, 0o600)
        except (OSError, ValueError) as exc:
            messagebox.showerror("Импорт", str(exc))
            return
        self.selected_name = dest.name
        self.refresh()
        self.log("Импортирован профиль " + dest.name)

    def delete(self):
        path = self.selected()
        if not path:
            return
        if self.active == path or self.service_is_running():
            messagebox.showwarning("NetherVPN", "Отключите VPN перед удалением профиля")
            return
        if messagebox.askyesno("Удалить профиль", "Удалить " + path.stem + "?"):
            path.unlink()
            self.preferences_path(path).unlink(missing_ok=True)
            self.selected_name = None
            self.refresh()
            self.log("Профиль удалён")

    def privileged(self, args, on_ok, on_error=None):
        """One polkit authentication per user-requested connect/disconnect."""
        if self.busy:
            return
        self.busy = True
        self.state.set("Выполняется операция…")
        def run():
            try:
                proc = subprocess.run(["pkexec", HELPER, *args],
                                      text=True, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE, timeout=120, check=False)
                if proc.returncode:
                    raise RuntimeError(proc.stderr.strip() or "Операция отклонена")
                self.after(0, lambda: self.finish_operation(on_ok, proc.stdout))
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as exc:
                message = str(exc)
                self.after(0, lambda: self.fail_operation(message, on_error))
        threading.Thread(target=run, daemon=True).start()

    def finish_operation(self, callback, output=""):
        # The systemd unit may have changed during pkexec: do not use the
        # previous 3s cached state (or clear the newly connected profile).
        self.last_service_poll = 0.0
        self.busy = False
        if output.strip():
            self.log(output.strip())
        callback()

    def fail_operation(self, message, callback=None):
        self.last_service_poll = 0.0
        self.busy = False
        self.state.set("Ошибка управления подключением")
        self.log("Ошибка: " + message[:600])
        if callback:
            callback()
        messagebox.showerror("NetherVPN", message[:800])

    def connect(self):
        if self.busy:
            return
        profile = self.selected()
        if not profile:
            messagebox.showinfo("NetherVPN", "Выберите профиль")
            return
        if not Path("/etc/systemd/system/nethervpn-client.service").exists():
            messagebox.showerror("NetherVPN", "Установите клиентскую службу: bash scripts/install-desktop.sh")
            return
        if not shutil.which("pkexec"):
            messagebox.showerror("NetherVPN", "Не найден pkexec")
            return
        preferences = self.save_settings(notify=False)
        if preferences is None:
            return
        mode = self.mode_var.get()
        if mode == "full" and not messagebox.askyesno(
            "Full Tunnel",
            "Весь IPv4 и DNS компьютера будут направлены через VPN. IPv6 будет "
            "заблокирован. При аварийном обрыве доступ в интернет может исчезнуть.\\n\\n"
            "Включить полный туннель?"
        ):
            return
        if not Path(HELPER).is_file():
            messagebox.showerror("NetherVPN",
                "Не найден помощник. Повторите: bash scripts/install-desktop.sh")
            return
        self.active = profile
        self.since = time.monotonic()
        self.previous_counters = None

        def started():
            self.log("Служба запущена: " + profile.stem)
            self.state.set("Ожидание защищённого соединения…")
            self.inspect_connection()

        def failed():
            self.active = None
            self.previous_counters = None

        # One pkexec operation for profile, settings, service and autostart.
        self.privileged(["connect", profile.name], started, failed)

    def disconnect(self):
        if self.busy:
            return
        def stopped():
            self.active = None
            self.previous_counters = None
            self.state.set("Не подключено")
            self.log("Служба NetherVPN остановлена")
        self.privileged(["disconnect"], stopped)

    def inspect_connection(self):
        """Check systemd and IPv4 route without additional authorization."""
        try:
            result = subprocess.run(
                ["systemctl", "show", "nethervpn-client.service",
                 "--property=ActiveState", "--property=SubState"],
                text=True, capture_output=True, timeout=3, check=False)
            self.log("systemd: " + result.stdout.strip().replace("\n", "; "))
            if self.mode_var.get() == "full":
                route = subprocess.run(
                    ["ip", "-4", "route", "get", "1.1.1.1"],
                    text=True, capture_output=True, timeout=3, check=False)
                self.log("Маршрут IPv4: " + (route.stdout or route.stderr).strip()[:300])
                if " dev nvpn0 " not in " " + route.stdout + " ":
                    self.log("IPv4 пока не проходит через nvpn0 — проверьте handshake, DNS и NAT сервера.")
            journal = subprocess.run(
                ["journalctl", "-u", "nethervpn-client.service",
                 "-n", "8", "--no-pager", "-o", "cat"],
                text=True, capture_output=True, timeout=3, check=False)
            if journal.returncode == 0 and journal.stdout.strip():
                self.log("Журнал службы: " + journal.stdout.strip()[-900:])
            elif journal.returncode:
                self.log("Журнал недоступен из GUI. Используйте: sudo journalctl -u nethervpn-client -n 50")
        except (OSError, subprocess.TimeoutExpired) as exc:
            self.log("Диагностика: " + str(exc))

    def read_log(self):
        if not self.logfile:
            return
        self.logfile.flush()
        self.logfile.seek(self.log_offset)
        content = self.logfile.read(32768)
        self.log_offset += len(content)
        for line in content.decode("utf-8", "replace").splitlines():
            if "client: encrypted tunnel active" in line:
                self.authenticated = True
            if "client: " in line and "retrying in" in line:
                self.authenticated = False
            self.log(line[:500])

    def stats(self):
        if not self.active:
            return False
        try:
            _, name, _, _ = profile_meta(self.active)
            base = Path("/sys/class/net") / name / "statistics"
            tx = int((base / "tx_bytes").read_text())
            rx = int((base / "rx_bytes").read_text())
        except (OSError, ValueError, configparser.Error):
            return False
        now = time.monotonic()
        if self.previous_counters:
            old_tx, old_rx, old_time = self.previous_counters
            seconds = max(now - old_time, 0.001)
            self.speed.set(f"↓ {max(0, rx - old_rx) / seconds / 1024:.1f} КиБ/с  ↑ {max(0, tx - old_tx) / seconds / 1024:.1f} КиБ/с")
        self.previous_counters = (tx, rx, now)
        self.tx.set(f"{tx / 1024:.1f} КиБ")
        self.rx.set(f"{rx / 1024:.1f} КиБ")
        return True

    def service_is_running(self):
        """Unprivileged systemd read; checked at most once every 3 seconds."""
        now = time.monotonic()
        if now - self.last_service_poll >= 3:
            self.last_service_poll = now
            try:
                result = subprocess.run(
                    ["systemctl", "is-active", "--quiet", "nethervpn-client.service"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                    timeout=2, check=False)
                self.service_running = result.returncode == 0
            except (OSError, subprocess.TimeoutExpired):
                self.service_running = False
        return self.service_running

    def update_power(self, running, ready=False):
        if self.busy:
            color = AMBER
            title = "Подключение…"
            subtitle = "Выполняется операция"
        elif ready:
            color = ACCENT
            title = "Отключить"
            subtitle = "VPN активен"
        elif running:
            color = AMBER
            title = "Отключить"
            subtitle = "Ожидание туннеля"
        else:
            color = "#536276"
            title = "Подключить"
            subtitle = "Нажмите для включения"
        self.connect_btn.itemconfigure(self.outer_ring, outline=color)
        self.connect_btn.itemconfigure(self.power_text, text=title, fill=TEXT)
        self.connect_btn.itemconfigure(self.power_subtext, text=subtitle, fill=MUTED)
        self.connect_btn.configure(state="disabled" if self.busy else "normal")

    def tick(self):
        running = self.service_is_running()
        active = self.active
        ready = False
        if running and active:
            ready = self.stats()
            if ready:
                self.state.set("Туннель подключён")
                self.status_tag.set("●  СОЕДИНЕНИЕ АКТИВНО")
                self.status_hint.set("VPN-интерфейс работает")
                self.status_badge.configure(fg=ACCENT)
            else:
                self.state.set("Подключение…")
                self.status_tag.set("●  ОЖИДАНИЕ ТУННЕЛЯ")
                self.status_hint.set("Подключаемся к серверу")
                self.status_badge.configure(fg=AMBER)
            if self.since:
                seconds = int(time.monotonic() - self.since)
                self.timing.set(
                    f"{seconds // 3600:02d}:{(seconds // 60) % 60:02d}:{seconds % 60:02d}")
        elif running:
            # The VPN may run as a boot service before opening this GUI.
            self.state.set("VPN работает в фоне")
            self.status_tag.set("●  VPN-СЛУЖБА ЗАПУЩЕНА")
            self.status_hint.set("Статус туннеля смотрите в диагностике")
            self.status_badge.configure(fg=AMBER)
        elif not self.busy:
            if self.active is not None:
                self.active = None
                self.previous_counters = None
                self.since = None
            self.state.set("VPN выключен")
            self.status_tag.set("●  НЕ ПОДКЛЮЧЕНО")
            self.status_hint.set("Можно выбрать другой сервер")
            self.status_badge.configure(fg=MUTED)
            self.timing.set("00:00:00")
        self.update_power(running, ready)
        self.after(1000, self.tick)

    def close_app(self):
        if self.busy:
            messagebox.showinfo("NetherVPN", "Дождитесь окончания операции")
            return
        if self.service_is_running() and not messagebox.askyesno("NetherVPN", "Закрыть окно? VPN продолжит работать как служба."):
            return
        self.destroy()

if __name__ == "__main__":
    App().mainloop()
