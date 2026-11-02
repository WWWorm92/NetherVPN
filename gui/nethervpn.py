#!/usr/bin/env python3
"""NetherVPN desktop profile manager (Linux, Tk). No shell invocation."""
import os
import pathlib
import shutil
import signal
import subprocess
import tkinter as tk
import time
from tkinter import filedialog, messagebox, ttk

HOME = pathlib.Path.home()
PROFILES = HOME / ".config" / "nethervpn" / "profiles"
ROOT = pathlib.Path(__file__).resolve().parent.parent
BINARY = ROOT / "bin" / "nether-client"

class Window(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("NetherVPN")
        self.geometry("650x410")
        self.minsize(540, 360)
        self.proc = None
        self.profiles = []
        self.active_profile = ''
        self.started_at = None
        PROFILES.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(PROFILES, 0o700)
        wrapper = ttk.Frame(self, padding=20)
        wrapper.pack(fill="both", expand=True)
        ttk.Label(wrapper, text="NetherVPN", font=("Sans", 21, "bold")).pack(anchor="w")
        ttk.Label(wrapper, text="Профили подключений • Linux", foreground="gray").pack(anchor="w", pady=(0, 15))
        self.listbox = tk.Listbox(wrapper, height=7)
        self.listbox.pack(fill="both", expand=True)
        bar = ttk.Frame(wrapper)
        bar.pack(fill="x", pady=12)
        ttk.Button(bar, text="Импорт .conf", command=self.import_profile).pack(side="left", padx=(0, 8))
        ttk.Button(bar, text="Подключить", command=self.connect).pack(side="left", padx=(0, 8))
        ttk.Button(bar, text="Отключить", command=self.disconnect).pack(side="left", padx=(0, 8))
        ttk.Button(bar, text="Удалить", command=self.delete_profile).pack(side="left")
        self.status = tk.StringVar(value="Не подключено")
        ttk.Label(wrapper, textvariable=self.status).pack(anchor="w")
        self.stats = tk.StringVar(value="Трафик: —")
        ttk.Label(wrapper, textvariable=self.stats).pack(anchor="w", pady=6)
        ttk.Label(wrapper, text="Нет изменения default route, DNS или системных VPN.", foreground="gray").pack(anchor="w", pady=(6, 0))
        self.refresh()
        self.after(350, self.poll)
        self.protocol("WM_DELETE_WINDOW", self.on_close)

    def refresh(self):
        self.profiles = sorted(PROFILES.glob("*.conf"))
        self.listbox.delete(0, "end")
        for item in self.profiles:
            self.listbox.insert("end", item.stem)

    def selected(self):
        s = self.listbox.curselection()
        return self.profiles[s[0]] if s else None

    def import_profile(self):
        source = filedialog.askopenfilename(filetypes=[("NetherVPN profiles", "*.conf")])
        if not source:
            return
        src = pathlib.Path(source)
        dest = PROFILES / src.name
        if dest.exists() and not messagebox.askyesno("Заменить профиль?", src.name):
            return
        if src.resolve() == dest.resolve():
            return
        # Copy without following attacker-controlled destination symlinks.
        if dest.is_symlink():
            messagebox.showerror("Ошибка", "Символьные ссылки для профилей запрещены")
            return
        content = src.read_bytes()
        fd = os.open(dest, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, "O_NOFOLLOW", 0), 0o600)
        with os.fdopen(fd, "wb") as out:
            out.write(content)
        os.chmod(dest, 0o600)
        self.refresh()
        self.status.set("Профиль импортирован: " + src.name)

    def connect(self):
        if self.proc and self.proc.poll() is None:
            messagebox.showinfo("NetherVPN", "Сначала отключите текущий профиль")
            return
        path = self.selected()
        if not path:
            messagebox.showinfo("NetherVPN", "Выберите профиль")
            return
        if not BINARY.is_file():
            messagebox.showerror("NetherVPN", "Сначала соберите клиент: go build -o bin/nether-client ./cmd/nether-client")
            return
        auth = shutil.which("pkexec")
        if not auth:
            messagebox.showerror("NetherVPN", "Установите polkit (pkexec) для запроса прав")
            return
        try:
            self.proc = subprocess.Popen([auth, str(BINARY), "-config", str(path)], start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            self.active_profile = path.stem
            self.started_at = time.monotonic()
            self.status.set("Подключение: " + path.stem)
        except OSError as e:
            messagebox.showerror("NetherVPN", str(e))

    def disconnect(self):
        if self.proc and self.proc.poll() is None:
            os.killpg(self.proc.pid, signal.SIGTERM)
            self.status.set("Отключение...")
        else:
            self.status.set("Не подключено")

    def delete_profile(self):
        path = self.selected()
        if path and messagebox.askyesno("Удалить?", path.name):
            path.unlink()
            self.refresh()

    def update_stats(self):
        base = pathlib.Path("/sys/class/net/nvpn0/statistics")
        try:
            tx = int((base / "tx_bytes").read_text())
            rx = int((base / "rx_bytes").read_text())
        except (OSError, ValueError):
            self.stats.set("Трафик: —")
            return False
        elapsed = int(time.monotonic() - self.started_at) if self.started_at else 0
        self.stats.set(f"Отправлено {tx / 1024:.1f} КиБ · Получено {rx / 1024:.1f} КиБ · {elapsed // 60} мин")
        return True

    def poll(self):
        if self.proc and self.proc.poll() is not None:
            # Surface errors without exposing profile private key in command-line.
            err = self.proc.stderr.read().decode("utf-8", "replace").strip()
            self.status.set("Отключено" + (": " + err[-200:] if err else ""))
            self.proc.stderr.close()
            self.proc = None
        elif self.proc and self.proc.poll() is None:
            if self.update_stats():
                self.status.set("Туннель активен: " + self.active_profile)
            else:
                self.status.set("Ожидание подключения / переподключение: " + self.active_profile)
        self.after(350, self.poll)

    def on_close(self):
        if self.proc and self.proc.poll() is None:
            if not messagebox.askyesno("NetherVPN", "Отключить VPN и закрыть окно?"):
                return
            self.disconnect()
        self.destroy()

if __name__ == "__main__":
    Window().mainloop()
