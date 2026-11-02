#!/usr/bin/env python3
"""NetherVPN Linux desktop client. No system routes changed by GUI."""
import configparser
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
    return cfg.get("Peer", "Endpoint", fallback="—"), cfg.get("Interface", "Name", fallback="nvpn0")

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
        self.logfile = None
        self.log_offset = 0
        self.active = None
        self.since = None
        self.items = []
        self.selected_name = None
        self.state = tk.StringVar(value="Не подключено")
        self.endpoint = tk.StringVar(value="Выберите профиль")
        self.timing = tk.StringVar(value="—")
        self.tx = tk.StringVar(value="—")
        self.rx = tk.StringVar(value="—")
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
                 font=("DejaVu Sans", 10), anchor="w").pack(fill="x", padx=24, pady=(7, 12))
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
        self.label(right, "ЖУРНАЛ СОБЫТИЙ", 10, MUTED, "bold").pack(anchor="w", padx=24, pady=(5, 7))
        self.logbox = tk.Text(right, bg="#101827", fg="#c5d4e7", height=7,
                              font=("DejaVu Sans Mono", 9), state="disabled", relief="flat", wrap="word")
        self.logbox.pack(fill="both", expand=True, padx=24, pady=(0, 18))
        self.log("Приложение запущено. Интернет-маршруты не изменяются.")
        foot = tk.Frame(self, bg=BG)
        foot.pack(fill="x", padx=28, pady=(12, 15))
        self.label(foot, "Трафик TUN ≠ подтверждённая доступность сервера. Только лабораторное использование.", 9, MUTED).pack()

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

    def on_select(self, _event=None):
        path = self.selected()
        if path:
            self.selected_name = path.name
            try:
                peer, _ = profile_meta(path)
                self.endpoint.set(peer)
            except (OSError, ValueError, configparser.Error) as exc:
                self.endpoint.set("Ошибка конфигурации")
                self.log(str(exc))
        elif not self.active:
            self.endpoint.set("Выберите профиль")

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
        if self.process and self.process.poll() is None:
            messagebox.showwarning("NetherVPN", "Отключите VPN перед удалением профиля")
            return
        if messagebox.askyesno("Удалить профиль", "Удалить " + path.stem + "?"):
            path.unlink()
            self.selected_name = None
            self.refresh()
            self.log("Профиль удалён")

    def connect(self):
        if self.process and self.process.poll() is None:
            messagebox.showinfo("NetherVPN", "Текущий профиль уже запущен")
            return
        profile = self.selected()
        if not profile:
            messagebox.showinfo("NetherVPN", "Выберите или импортируйте профиль")
            return
        if not CLIENT.is_file():
            messagebox.showerror("Нет клиента", "Соберите: go build -o bin/nether-client ./cmd/nether-client")
            return
        auth = shutil.which("pkexec")
        if not auth:
            messagebox.showerror("Нет polkit", "Установите пакет policykit-1")
            return
        try:
            self.logfile = tempfile.TemporaryFile(mode="w+b")
            self.log_offset = 0
            self.process = subprocess.Popen([auth, str(CLIENT), "-config", str(profile)],
                                            stdout=self.logfile, stderr=subprocess.STDOUT,
                                            start_new_session=True)
        except OSError as exc:
            self.log("Ошибка запуска: " + str(exc))
            messagebox.showerror("NetherVPN", str(exc))
            return
        self.active = profile
        self.since = time.monotonic()
        self.state.set("Подключение…")
        self.log("Запрос подключения: " + profile.stem)

    def disconnect(self):
        if self.process and self.process.poll() is None:
            try:
                os.killpg(self.process.pid, signal.SIGTERM)
                self.state.set("Отключение…")
                self.log("Запрошено отключение")
            except ProcessLookupError:
                pass
        else:
            self.state.set("Не подключено")

    def read_log(self):
        if not self.logfile:
            return
        self.logfile.flush()
        self.logfile.seek(self.log_offset)
        content = self.logfile.read(32768)
        self.log_offset += len(content)
        for line in content.decode("utf-8", "replace").splitlines():
            self.log(line[:500])

    def stats(self):
        if not self.active:
            return False
        try:
            _, name = profile_meta(self.active)
            base = Path("/sys/class/net") / name / "statistics"
            tx = int((base / "tx_bytes").read_text())
            rx = int((base / "rx_bytes").read_text())
        except (OSError, ValueError, configparser.Error):
            return False
        self.tx.set(f"{tx / 1024:.1f} КиБ")
        self.rx.set(f"{rx / 1024:.1f} КиБ")
        return True

    def tick(self):
        if self.process:
            self.read_log()
            if self.process.poll() is not None:
                code = self.process.returncode
                self.log("Клиент завершился (код " + str(code) + ")")
                self.state.set("Отключено")
                self.process = None
                self.active = None
                self.since = None
                if self.logfile:
                    self.logfile.close()
                    self.logfile = None
            else:
                self.state.set("Туннель активен" if self.stats() else "Ожидание подключения…")
                if self.since:
                    seconds = int(time.monotonic() - self.since)
                    self.timing.set(f"{seconds // 60:02d}:{seconds % 60:02d}")
        self.after(500, self.tick)

    def close_app(self):
        if self.process and self.process.poll() is None:
            if not messagebox.askyesno("NetherVPN", "Отключить VPN и закрыть приложение?"):
                return
            self.disconnect()
        self.destroy()

if __name__ == "__main__":
    App().mainloop()
