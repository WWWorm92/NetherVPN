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
        self.geometry("1130x790")
        self.minsize(870, 610)
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
        self.items = []
        self.selected_name = None
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

    def heading(self, parent, title, subtitle=None):
        self.label(parent, title, 10, MUTED, "bold").pack(anchor="w")
        if subtitle:
            self.label(parent, subtitle, 11, MUTED).pack(anchor="w", pady=(6, 0))

    def _ui(self):
        # Spacious, vertically scrollable dashboard: no controls disappear
        # below the screen on 900x650 laptops.
        top = tk.Frame(self, bg=BG)
        top.pack(fill="x", padx=30, pady=(23, 16))
        logo = tk.Label(top, text="◈", bg=RAISED, fg=ACCENT,
                        font=("DejaVu Sans", 25, "bold"), width=3, height=1)
        logo.pack(side="left")
        brand = tk.Frame(top, bg=BG)
        brand.pack(side="left", padx=13)
        self.label(brand, "NetherVPN", 23, TEXT, "bold").pack(anchor="w")
        self.label(brand, "ЗАЩИЩЁННАЯ СЕТЬ  /  LINUX", 9, MUTED, "bold").pack(anchor="w")
        self.label(top, "EXP  •  Noise IK  •  IPv4", 10, MUTED, "bold").pack(
            side="right", pady=(12, 0))

        body = tk.Frame(self, bg=BG)
        body.pack(fill="both", expand=True, padx=30, pady=(0, 16))

        sidebar = tk.Frame(body, bg=PANEL, width=238)
        sidebar.pack(side="left", fill="y", padx=(0, 16))
        sidebar.pack_propagate(False)
        self.label(sidebar, "ПРОФИЛИ", 10, MUTED, "bold").pack(
            anchor="w", padx=19, pady=(22, 4))
        self.label(sidebar, "Ваши устройства", 12, TEXT).pack(
            anchor="w", padx=19, pady=(0, 14))
        self.listbox = tk.Listbox(sidebar, bg=PANEL, fg=TEXT,
                                 font=("DejaVu Sans", 12), relief="flat",
                                 selectbackground="#225d62",
                                 selectforeground=TEXT,
                                 activestyle="none", bd=0,
                                 highlightthickness=0, exportselection=False)
        self.listbox.pack(fill="both", expand=True, padx=13)
        self.listbox.bind("<<ListboxSelect>>", self.on_select)
        manage = tk.Frame(sidebar, bg=PANEL)
        manage.pack(fill="x", padx=13, pady=(13, 15))
        self.button(manage, "+ Импорт", self.import_profile, True).pack(
            side="left", padx=(0, 7))
        self.button(manage, "Удалить", self.delete).pack(side="left")
        self.label(sidebar, "Каждому устройству — свой ключ.",
                   9, MUTED).pack(anchor="w", padx=19, pady=(0, 18))

        scrollframe = tk.Frame(body, bg=BG)
        scrollframe.pack(side="left", fill="both", expand=True)
        canvas = tk.Canvas(scrollframe, bg=BG, relief="flat",
                           highlightthickness=0, bd=0)
        scrollbar = tk.Scrollbar(scrollframe, orient="vertical",
                                 command=canvas.yview)
        scrollbar.pack(side="right", fill="y")
        canvas.pack(side="left", fill="both", expand=True)
        canvas.configure(yscrollcommand=scrollbar.set)
        inner = tk.Frame(canvas, bg=BG)
        window = canvas.create_window((0, 0), window=inner, anchor="nw")
        inner.bind("<Configure>",
                   lambda _e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>",
                    lambda event: canvas.itemconfigure(window, width=event.width))
        def wheel(event):
            if event.num == 4:
                amount = -3
            elif event.num == 5:
                amount = 3
            else:
                amount = -int(event.delta / 120) * 3
            canvas.yview_scroll(amount, "units")
        canvas.bind("<MouseWheel>", wheel)
        canvas.bind("<Button-4>", wheel)
        canvas.bind("<Button-5>", wheel)
        inner.bind("<MouseWheel>", wheel)

        hero = tk.Frame(inner, bg="#1a3445", padx=24, pady=21)
        hero.pack(fill="x", pady=(0, 14))
        self.heading(hero, "ЗАЩИЩЁННОЕ СОЕДИНЕНИЕ")
        self.status_badge = tk.Label(hero, textvariable=self.status_tag,
                                     bg="#1a3445", fg=MUTED,
                                     font=("DejaVu Sans", 10, "bold"),
                                     anchor="w")
        self.status_badge.pack(anchor="w", pady=(16, 8))
        tk.Label(hero, textvariable=self.state, bg="#1a3445", fg=TEXT,
                 font=("DejaVu Sans", 22, "bold"), anchor="w").pack(fill="x")
        tk.Label(hero, textvariable=self.status_hint, bg="#1a3445", fg=MUTED,
                 font=("DejaVu Sans", 10), anchor="w").pack(fill="x", pady=(5, 13))
        tk.Label(hero, textvariable=self.endpoint, bg="#1a3445", fg=ACCENT,
                 font=("DejaVu Sans Mono", 10), anchor="w").pack(fill="x")
        row = tk.Frame(hero, bg="#1a3445")
        row.pack(anchor="w", pady=(18, 0))
        self.connect_btn = self.button(row, "◈  Подключить", self.connect, True)
        self.connect_btn.pack(side="left", padx=(0, 9))
        self.disconnect_btn = self.button(row, "Отключить", self.disconnect)
        self.disconnect_btn.pack(side="left", padx=(0, 9))
        self.button(row, "Диагностика ↗", self.inspect_connection).pack(
            side="left")

        stats = tk.Frame(inner, bg=BG)
        stats.pack(fill="x", pady=(0, 14))
        for title, var in (("↑ ОТПРАВЛЕНО", self.tx),
                           ("↓ ПОЛУЧЕНО", self.rx),
                           ("ВРЕМЯ В СЕТИ", self.timing)):
            cell = tk.Frame(stats, bg=PANEL, padx=15, pady=14)
            cell.pack(side="left", fill="both", expand=True, padx=(0, 9))
            self.label(cell, title, 9, MUTED, "bold").pack(anchor="w")
            tk.Label(cell, textvariable=var, bg=PANEL, fg=TEXT,
                     font=("DejaVu Sans", 16, "bold"),
                     anchor="w").pack(anchor="w", pady=(9, 0))
        tk.Label(inner, textvariable=self.speed, bg=BG, fg=ACCENT,
                 font=("DejaVu Sans", 10, "bold"), anchor="w").pack(
                     fill="x", pady=(0, 14))

        settings = tk.Frame(inner, bg=PANEL, padx=22, pady=20)
        settings.pack(fill="x", pady=(0, 14))
        self.heading(settings, "НАСТРОЙКИ ПОДКЛЮЧЕНИЯ",
                     "Выберите, какой трафик направлять через VPN.")
        choices = tk.Frame(settings, bg=PANEL)
        choices.pack(fill="x", pady=(14, 12))
        self.mode_buttons = {}
        for value, title, caption in (
            ("full", "Весь IPv4 через VPN", "Весь интернет-трафик через сервер"),
            ("split", "Раздельный туннель", "Только сеть VPN-сервера"),
        ):
            cell = tk.Frame(choices, bg=RAISED, padx=12, pady=10)
            cell.pack(side="left", fill="both", expand=True,
                      padx=(0, 10) if value == "full" else 0)
            radio = tk.Radiobutton(
                cell, text=title, variable=self.mode_var, value=value,
                indicatoron=False, bg=RAISED, fg=TEXT, selectcolor=RAISED,
                activebackground="#23545b", activeforeground=TEXT,
                font=("DejaVu Sans", 10, "bold"), relief="flat",
                bd=0, padx=8, pady=8, cursor="hand2",
                command=self.refresh_mode_label)
            radio.pack(fill="x")
            self.label(cell, caption, 9, MUTED).pack(anchor="w", padx=7)
            self.mode_buttons[value] = radio

        self.label(settings, "DNS для Full Tunnel", 10, MUTED, "bold").pack(
            anchor="w", pady=(6, 7))
        tk.Entry(settings, textvariable=self.dns_var, bg=RAISED, fg=TEXT,
                 insertbackground=TEXT, relief="flat", bd=0,
                 font=("DejaVu Sans Mono", 12)).pack(
                     fill="x", ipady=10)
        tk.Checkbutton(
            settings, text="Подключать при запуске Linux",
            variable=self.autostart_var, bg=PANEL, fg=TEXT,
            selectcolor=RAISED, activebackground=PANEL,
            activeforeground=TEXT, font=("DejaVu Sans", 10),
            relief="flat", cursor="hand2").pack(anchor="w", pady=(11, 10))
        controls = tk.Frame(settings, bg=PANEL)
        controls.pack(fill="x")
        self.button(controls, "Сохранить настройки",
                    self.save_settings).pack(side="left")
        tk.Label(controls, textvariable=self.mode_text, bg=PANEL,
                 fg=ACCENT, font=("DejaVu Sans", 9),
                 anchor="w").pack(side="left", padx=13)

        journal = tk.Frame(inner, bg=PANEL, padx=22, pady=18)
        journal.pack(fill="both", expand=True, pady=(0, 14))
        self.heading(journal, "ЖУРНАЛ СОБЫТИЙ",
                     "Сообщения о подключении и диагностике.")
        self.logbox = tk.Text(journal, bg=BG, fg="#d0dfed",
                              insertbackground=TEXT, height=7, bd=0,
                              font=("DejaVu Sans Mono", 9), state="disabled",
                              relief="flat", wrap="word", padx=10, pady=10)
        self.logbox.pack(fill="both", expand=True, pady=(12, 0))
        self.log("NetherVPN готов. Режим и DNS выбираются локально.")

        self.label(inner,
                   "Full Tunnel: IPv4 и DNS через VPN; IPv6 блокируется. "
                   "Для блокировки после сбоя проверяйте политику маршрутов.",
                   9, MUTED).pack(anchor="w", pady=(0, 14))
        self.refresh_mode_label()

    def refresh_mode_buttons(self):
        if not hasattr(self, "mode_buttons"):
            return
        for value, radio in self.mode_buttons.items():
            chosen = self.mode_var.get() == value
            radio.configure(bg="#225d62" if chosen else RAISED,
                            activebackground="#225d62" if chosen else RAISED,
                            fg=TEXT)

    def log(self, msg):
        line = time.strftime("%H:%M:%S") + "  " + msg.rstrip() + "\n"
        self.logbox.configure(state="normal")
        self.logbox.insert("end", line)
        self.logbox.see("end")
        self.logbox.configure(state="disabled")

    def refresh(self):
        name = self.selected_name
        self.items = sorted(p for p in BASE.glob("*.conf") if p.is_file() and not p.is_symlink())
        self.listbox.delete(0, "end")
        for p in self.items:
            self.listbox.insert("end", p.stem)
        for i, p in enumerate(self.items):
            if p.name == name:
                self.listbox.selection_set(i)
                self.listbox.activate(i)
        self.on_select()

    def selected(self):
        sel = self.listbox.curselection()
        return self.items[sel[0]] if sel and sel[0] < len(self.items) else None

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
            self.mode_text.set("Весь IPv4 через VPN · DNS " + self.dns_var.get()
                               + " · IPv6 блокируется")
        else:
            self.mode_text.set("Только VPN-сервер · интернет без изменений")

    def on_select(self, _event=None):
        path = self.selected()
        if path:
            self.selected_name = path.name
            try:
                peer, _, _, _ = profile_meta(path)
                mode, dns, auto = self.load_choices(path)
                self.endpoint.set(peer)
                self.mode_var.set(mode)
                self.dns_var.set(dns)
                self.autostart_var.set(auto)
                self.refresh_mode_label()
            except (OSError, ValueError, configparser.Error) as exc:
                self.endpoint.set("Ошибка конфигурации")
                self.log(str(exc))
        elif not self.active:
            self.endpoint.set("Выберите профиль")
            self.mode_text.set("Выберите профиль")

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
        if self.active == path:
            messagebox.showwarning("NetherVPN", "Отключите VPN перед удалением активного профиля")
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
        self.busy = False
        if output.strip():
            self.log(output.strip())
        callback()

    def fail_operation(self, message, callback=None):
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

    def tick(self):
        if self.active:
            has_tun = self.stats()
            self.state.set("Туннель запущен" if has_tun else "Ожидание интерфейса…")
            if self.since:
                seconds = int(time.monotonic() - self.since)
                self.timing.set(f"{seconds // 60:02d}:{seconds % 60:02d}")
        self.after(750, self.tick)

    def close_app(self):
        if self.busy:
            messagebox.showinfo("NetherVPN", "Дождитесь окончания операции")
            return
        if self.active and not messagebox.askyesno("NetherVPN", "Закрыть окно? VPN продолжит работать как служба."):
            return
        self.destroy()

if __name__ == "__main__":
    App().mainloop()
