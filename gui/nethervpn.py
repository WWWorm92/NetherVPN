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
BG = "#101827"
PANEL = "#192539"
TEXT = "#edf4ff"
MUTED = "#9db1c8"
ACCENT = "#43caaf"

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
        self.geometry("860x610")
        self.minsize(720, 510)
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
        self._ui()
        self.refresh()
        self.after(500, self.tick)
        self.protocol("WM_DELETE_WINDOW", self.close_app)

    def label(self, parent, text, size=11, fg=TEXT, weight="normal"):
        return tk.Label(parent, text=text, bg=parent.cget("bg"), fg=fg,
                        font=("DejaVu Sans", size, weight), anchor="w")

    def button(self, parent, text, command, primary=False):
        return tk.Button(parent, text=text, command=command, font=("DejaVu Sans", 10, "bold"),
                         bg=ACCENT if primary else "#304157", fg="#0a1721" if primary else TEXT,
                         activebackground="#69e0c9", relief="flat", padx=13, pady=9,
                         cursor="hand2", bd=0)

    def _ui(self):
        header = tk.Frame(self, bg=BG)
        header.pack(fill="x", padx=28, pady=(22, 16))
        self.label(header, "◈  NetherVPN", 23, TEXT, "bold").pack(side="left")
        self.label(header, "Linux · защищённый туннель · экспериментальная версия", 10, MUTED).pack(side="right", pady=12)
        body = tk.Frame(self, bg=BG)
        body.pack(fill="both", expand=True, padx=28)
        left = tk.Frame(body, bg=PANEL, width=260)
        left.pack(side="left", fill="y", padx=(0, 15))
        left.pack_propagate(False)
        self.label(left, "ПРОФИЛИ", 10, MUTED, "bold").pack(anchor="w", padx=18, pady=(20, 8))
        self.listbox = tk.Listbox(left, bg=PANEL, fg=TEXT, font=("DejaVu Sans", 12),
                                 selectbackground="#2a897f", selectforeground="white",
                                 activestyle="none", border=0, highlightthickness=0,
                                 exportselection=False)
        self.listbox.pack(fill="both", expand=True, padx=12)
        self.listbox.bind("<<ListboxSelect>>", self.on_select)
        row = tk.Frame(left, bg=PANEL)
        row.pack(fill="x", padx=10, pady=(12, 18))
        self.button(row, "+ Импорт", self.import_profile).pack(side="left", padx=(0, 6))
        self.button(row, "Удалить", self.delete).pack(side="left")
        right = tk.Frame(body, bg=PANEL)
        right.pack(side="left", fill="both", expand=True)
        self.label(right, "СОЕДИНЕНИЕ", 10, MUTED, "bold").pack(anchor="w", padx=24, pady=(23, 8))
        self.label(right, "●", 29, ACCENT).pack(anchor="w", padx=24)
        tk.Label(right, textvariable=self.state, bg=PANEL, fg=TEXT,
                 font=("DejaVu Sans", 17, "bold"), anchor="w").pack(fill="x", padx=24)
        tk.Label(right, textvariable=self.endpoint, bg=PANEL, fg=MUTED,
                 font=("DejaVu Sans", 10), anchor="w").pack(fill="x", padx=24, pady=(7, 3))
        tk.Label(right, textvariable=self.mode_text, bg=PANEL, fg=ACCENT,
                 font=("DejaVu Sans", 10, "bold"), anchor="w").pack(fill="x", padx=24, pady=(0, 12))
        settings_panel = tk.Frame(right, bg=PANEL)
        settings_panel.pack(fill="x", padx=24, pady=(4, 12))
        self.label(settings_panel, "РЕЖИМ ПОДКЛЮЧЕНИЯ", 10, MUTED, "bold").pack(anchor="w")
        for value, caption in (("full", "Весь IPv4 через VPN"),
                               ("split", "Только адрес VPN-сервера")):
            tk.Radiobutton(
                settings_panel, text=caption, variable=self.mode_var, value=value,
                bg=PANEL, fg=TEXT, selectcolor=BG, activebackground=PANEL,
                activeforeground=TEXT, font=("DejaVu Sans", 10),
                command=self.refresh_mode_label,
            ).pack(anchor="w")
        self.label(settings_panel, "DNS для полного туннеля", 10, MUTED).pack(anchor="w")
        tk.Entry(settings_panel, textvariable=self.dns_var, bg="#304157",
                 fg=TEXT, insertbackground=TEXT, relief="flat",
                 font=("DejaVu Sans Mono", 11)).pack(fill="x", pady=(2, 8))
        tk.Checkbutton(
            settings_panel, text="Подключаться при запуске Linux (systemd)",
            variable=self.autostart_var, bg=PANEL, fg=TEXT,
            selectcolor=BG, activebackground=PANEL,
            activeforeground=TEXT,
        ).pack(anchor="w")
        self.button(settings_panel, "Сохранить настройки", self.save_settings).pack(anchor="w", pady=(4, 4))
        self.label(settings_panel, "В Full Tunnel блокировка утечек при переподключении встроена.\n"
                   "Для аварийного выключения: sudo systemctl stop nethervpn-client",
                   9, MUTED).pack(anchor="w")
        actions = tk.Frame(right, bg=PANEL)
        actions.pack(anchor="w", padx=24)
        self.connect_btn = self.button(actions, "Подключить", self.connect, True)
        self.connect_btn.pack(side="left", padx=(0, 9))
        self.button(actions, "Отключить", self.disconnect).pack(side="left")
        stats = tk.Frame(right, bg=PANEL)
        stats.pack(fill="x", padx=24, pady=(24, 14))
        for title, var in (("ОТПРАВЛЕНО", self.tx), ("ПОЛУЧЕНО", self.rx), ("ВРЕМЯ", self.timing)):
            cell = tk.Frame(stats, bg="#233247")
            cell.pack(side="left", fill="x", expand=True, padx=(0, 7))
            self.label(cell, title, 9, MUTED).pack(padx=10, pady=(9, 3))
            tk.Label(cell, textvariable=var, bg="#233247", fg=TEXT,
                     font=("DejaVu Sans", 12, "bold")).pack(anchor="w", padx=10, pady=(0, 10))
        tk.Label(right, textvariable=self.speed, bg=PANEL, fg=ACCENT, font=("DejaVu Sans", 11, "bold"), anchor="w").pack(fill="x", padx=24, pady=(0, 10))
        self.label(right, "ЖУРНАЛ СОБЫТИЙ", 10, MUTED, "bold").pack(anchor="w", padx=24, pady=(5, 7))
        self.logbox = tk.Text(right, bg="#101827", fg="#c5d4e7", height=7,
                              font=("DejaVu Sans Mono", 9), state="disabled", relief="flat", wrap="word")
        self.logbox.pack(fill="both", expand=True, padx=24, pady=(0, 18))
        self.log("Приложение запущено. Full Tunnel управляется системной службой.")
        foot = tk.Frame(self, bg=BG)
        foot.pack(fill="x", padx=28, pady=(12, 15))
        self.label(foot, "Full Tunnel перехватывает IPv4/DNS всей системы; IPv6 блокируется. При остановке служба возвращает обычную сеть.", 9, MUTED).pack()

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

    def privileged(self, args, on_ok):
        if self.busy:
            return
        self.busy = True
        self.state.set("Выполняется операция…")
        def run():
            try:
                proc = subprocess.run(["pkexec", *args], text=True,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                      timeout=45, check=False)
                if proc.returncode:
                    raise RuntimeError(proc.stderr.strip() or "Команда завершилась с ошибкой")
                self.after(0, lambda: (setattr(self, "busy", False), on_ok()))
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as exc:
                message = str(exc)
                self.after(0, lambda: self.log("Ошибка: " + message))
                self.after(0, lambda: self.state.set("Ошибка управления подключением"))
                self.after(0, lambda: setattr(self, "busy", False))
            finally:
                pass
        threading.Thread(target=run, daemon=True).start()

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
        self.active = profile
        self.since = time.monotonic()
        self.previous_counters = None

        def launched():
            self.log("NetherVPN запущен: " + profile.stem)
            self.state.set("Проверка соединения…")
            # Applies on next machine boot; does not disturb this session.
            command = "enable" if self.autostart_var.get() else "disable"
            self.privileged(
                ["/usr/bin/systemctl", command, "nethervpn-client.service"],
                lambda: self.log("Автоподключение при запуске ОС: " +
                                 ("включено" if command == "enable" else "отключено"))
            )

        def settings_installed():
            self.privileged(
                ["/usr/bin/systemctl", "restart", "nethervpn-client.service"],
                launched
            )

        def profile_installed():
            self.privileged([
                "/usr/bin/install", "-m", "600", "-o", "root", "-g", "root",
                str(preferences), "/etc/nethervpn/client-settings.conf"
            ], settings_installed)

        # Both identity and preferences are copied with 0600; only systemd
        # starts the privileged client. The private key never enters argv.
        self.privileged([
            "/usr/bin/install", "-m", "600", "-o", "root", "-g", "root",
            str(profile), "/etc/nethervpn/client.conf"
        ], profile_installed)

    def disconnect(self):
        if self.busy:
            return
        def stopped():
            self.active = None
            self.previous_counters = None
            self.state.set("Не подключено")
            self.log("Служба NetherVPN остановлена")
        self.privileged(["/usr/bin/systemctl", "stop", "nethervpn-client.service"], stopped)

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
