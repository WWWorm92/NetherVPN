#!/usr/bin/env python3
"""NetherVPN Qt desktop. GUI stays unprivileged; only fixed pkexec helper is root."""
import configparser
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import shutil

from PySide6.QtCore import Qt, QTimer, QThread, Signal, QRectF, QEasingCurve, QPropertyAnimation, Property
from PySide6.QtGui import QColor, QPainter, QPen, QFont, QIcon, QConicalGradient
from PySide6.QtWidgets import (
    QApplication, QWidget, QMainWindow, QLabel, QPushButton, QVBoxLayout,
    QHBoxLayout, QFrame, QStackedWidget, QListWidget, QLineEdit, QComboBox,
    QCheckBox, QDialog, QDialogButtonBox, QFileDialog, QMessageBox, QGridLayout,
    QPlainTextEdit, QSizePolicy, QScrollArea
)
from client_preferences import encode, decode

BASE = Path.home() / ".config" / "nethervpn" / "profiles"
ROOT = Path(__file__).resolve().parent.parent
HELPER = "/usr/local/libexec/nether-desktop-helper"

BG = "#0c1727"
SIDE = "#0a1322"
CARD = "#14263b"
CARD2 = "#192f46"
MINT = "#44e5cb"
TEXT = "#eff8ff"
MUTED = "#95b0c5"
RED = "#fb7185"
AMBER = "#f7c574"

STYLE = """
QWidget { background: #0c1727; color: #eff8ff; font-family: 'DejaVu Sans', sans-serif; font-size: 12px; }
QWidget#sidebar { background: #0a1322; }
QFrame#card { background: #14263b; border: 1px solid #213d55; border-radius: 22px; }
QFrame#hero { background: #102638; border: 1px solid #1f4b60; border-radius: 26px; }
QLabel#muted { color: #95b0c5; background: transparent; }
QLabel#heading { font-size: 29px; font-weight: 700; background: transparent; }
QLabel#metric { font-size: 19px; font-weight: 700; background: transparent; }
QLabel#brand { font-size: 21px; font-weight: 700; background: transparent; }
QLabel#tag { color: #44e5cb; font-size: 12px; font-weight: 700; background: transparent; }
QPushButton { background: #213c54; border: none; border-radius: 15px; padding: 12px 18px; font-weight: 600; }
QPushButton:hover { background: #30536d; }
QPushButton:pressed { background: #376478; }
QPushButton#nav { text-align: left; background: transparent; border-radius: 15px; padding: 13px 18px; color: #b7cddd; }
QPushButton#nav:hover { background: #16354b; }
QPushButton#nav[active="true"] { background: #12434d; color: #44e5cb; }
QPushButton#primary { background: #44e5cb; color: #081a28; font-weight: 700; }
QPushButton#primary:hover { background: #7af3df; }
QPushButton#danger { background: #4c263e; border: 1px solid #fb7185; color: #ffd1dc; font-weight: 700; }
QPushButton#danger:hover { background: #65334b; }
QLineEdit, QComboBox, QListWidget, QPlainTextEdit { background: #172d43; border: 1px solid #2c4c62; border-radius: 12px; padding: 10px; selection-background-color: #236b75; }
QComboBox::drop-down { border: 0px; width: 28px; }
QCheckBox { spacing: 12px; background: transparent; }
QScrollArea, QScrollArea > QWidget > QWidget { border: none; background: transparent; }
QDialog { background: #0c1727; }
"""

def label(text, size=None, muted=False, name=None):
    obj = QLabel(text)
    if name:
        obj.setObjectName(name)
    elif muted:
        obj.setObjectName("muted")
    if size:
        f = obj.font()
        f.setPointSize(size)
        obj.setFont(f)
    return obj

def card():
    c = QFrame()
    c.setObjectName("card")
    return c

def profile_meta(path):
    cp = configparser.ConfigParser(interpolation=None)
    cp.read(path)
    return (cp.get("Peer", "Endpoint", fallback="—"),
            cp.get("Interface", "Name", fallback="nvpn0"),
            cp.getboolean("Interface", "FullTunnel", fallback=False),
            cp.get("Interface", "DNS", fallback="1.1.1.1"))

class Ring(QWidget):
    clicked = Signal()
    def __init__(self):
        super().__init__()
        self.setMinimumSize(216, 216)
        self.setMaximumSize(240, 240)
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)
        self._progress = 0.0
        self.title = "Подключить"
        self.subtitle = "Нажмите для включения"
        self.color = QColor(MUTED)

    def get_progress(self):
        return self._progress
    def set_progress(self, value):
        self._progress = value
        self.update()
    progress = Property(float, get_progress, set_progress)

    def state(self, title, subtitle, color):
        self.title, self.subtitle, self.color = title, subtitle, QColor(color)
        self.animation.stop()
        self.animation.setStartValue(self._progress)
        self.animation.setEndValue(1.0 if color == MINT else .18)
        self.animation.start()
        self.update()

    def paintEvent(self, _event):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        span = min(self.width(), self.height())
        d = span - 24
        rect = QRectF((self.width()-d)/2, (self.height()-d)/2, d, d)
        p.setBrush(QColor("#102d40"))
        p.setPen(QPen(QColor("#2a485e"), 5))
        p.drawEllipse(rect)
        p.setPen(QPen(QColor("#41647b"), 1))
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.drawEllipse(rect.adjusted(11, 11, -11, -11))
        p.setPen(QPen(self.color, 5, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
        p.drawArc(rect, 70*16, -int((35 + 310*self._progress)*16))
        p.setPen(QColor(TEXT))
        f = QFont("DejaVu Sans", 17)
        f.setBold(True)
        p.setFont(f)
        p.drawText(rect.adjusted(0, -14, 0, 0), Qt.AlignmentFlag.AlignCenter, self.title)
        f.setPointSize(9)
        f.setBold(False)
        p.setFont(f)
        p.setPen(QColor(MUTED))
        p.drawText(rect.adjusted(0, 45, 0, 0), Qt.AlignmentFlag.AlignCenter, self.subtitle)

    def mouseReleaseEvent(self, e):
        if e.button() == Qt.MouseButton.LeftButton:
            self.clicked.emit()
    def keyPressEvent(self, e):
        if e.key() in (Qt.Key.Key_Return, Qt.Key.Key_Enter, Qt.Key.Key_Space):
            self.clicked.emit()
        else:
            super().keyPressEvent(e)

class PrivilegedWorker(QThread):
    finished_operation = Signal(bool, str)
    def __init__(self, args):
        super().__init__()
        self.args = args
    def run(self):
        try:
            p = subprocess.run(["pkexec", HELPER, *self.args],
                               text=True, capture_output=True, timeout=120, check=False)
            self.finished_operation.emit(p.returncode == 0,
                                         (p.stdout if p.returncode == 0 else p.stderr).strip())
        except (OSError, subprocess.TimeoutExpired) as e:
            self.finished_operation.emit(False, str(e))

class Window(QMainWindow):
    def __init__(self):
        super().__init__()
        BASE.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(BASE, 0o700)
        self.setWindowTitle("NetherVPN")
        self.setWindowIcon(QIcon(str(ROOT/"assets/nethervpn.svg")))
        self.resize(1050, 750)
        self.setMinimumSize(850, 650)
        self.busy = False
        self.worker = None
        self.current = None
        self.started_at = None
        self.last_counters = None
        self.profiles = []
        self.mode = "split"
        self.dns_value = "1.1.1.1"
        self.autostart = False
        self.events = []
        self._build()
        self.ring.animation = QPropertyAnimation(self.ring, b"progress", self)
        self.ring.animation.setDuration(450)
        self.ring.animation.setEasingCurve(QEasingCurve.Type.OutCubic)
        self.refresh_profiles()
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.tick)
        self.timer.start(1000)
        self.tick()

    def _build(self):
        shell = QWidget()
        self.setCentralWidget(shell)
        row = QHBoxLayout(shell)
        row.setContentsMargins(0, 0, 0, 0)
        row.setSpacing(0)
        sidebar = QWidget()
        sidebar.setObjectName("sidebar")
        sidebar.setFixedWidth(235)
        side = QVBoxLayout(sidebar)
        side.setContentsMargins(19, 27, 19, 22)
        side.setSpacing(7)
        side.addWidget(label("◈  NetherVPN", name="brand"))
        side.addSpacing(35)
        self.nav_buttons = []
        for title, page in (("⌂   Подключение", 0),
                            ("▤   Серверы", 1),
                            ("◫   Профили", 1),
                            ("▥   Статистика", 2),
                            ("⚙   Настройки", 3),
                            ("ⓘ   О программе", 4)):
            btn = QPushButton(title)
            btn.setObjectName("nav")
            btn.setProperty("active", page == 0)
            btn.clicked.connect(lambda _checked=False, page=page: self.go(page))
            side.addWidget(btn)
            self.nav_buttons.append((btn, page))
        side.addStretch()
        self.side_status = label("●  Служба остановлена", muted=True)
        side.addWidget(self.side_status)
        side.addWidget(label("NetherVPN · Linux", muted=True))
        row.addWidget(sidebar)
        self.pages = QStackedWidget()
        row.addWidget(self.pages, 1)
        self._build_home()
        self._build_profiles()
        self._build_statistics()
        self._build_settings()
        self._build_about()

    def _page(self, title, caption):
        wrapper = QWidget()
        lay = QVBoxLayout(wrapper)
        lay.setContentsMargins(35, 27, 35, 24)
        lay.setSpacing(15)
        lay.addWidget(label(title, name="heading"))
        lay.addWidget(label(caption, muted=True))
        return wrapper, lay

    def _build_home(self):
        page, layout = self._page("NetherVPN", "Приватная сеть под вашим контролем")
        hero = QFrame()
        hero.setObjectName("hero")
        h = QVBoxLayout(hero)
        h.setContentsMargins(18, 8, 18, 15)
        h.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.ring = Ring()
        self.ring.clicked.connect(self.toggle)
        h.addWidget(self.ring, alignment=Qt.AlignmentFlag.AlignCenter)
        self.status = label("●  Не подключено", name="tag")
        self.hint = label("Выберите профиль для начала работы", muted=True)
        h.addWidget(self.status, alignment=Qt.AlignmentFlag.AlignCenter)
        h.addWidget(self.hint, alignment=Qt.AlignmentFlag.AlignCenter)
        layout.addWidget(hero, 2)

        route = card()
        r = QHBoxLayout(route)
        r.setContentsMargins(22, 12, 17, 12)
        route_labels = QVBoxLayout()
        route_labels.addWidget(label("РЕЖИМ ТУННЕЛЯ", muted=True))
        self.mode_label = label("Только VPN-сервер", size=13)
        route_labels.addWidget(self.mode_label)
        r.addLayout(route_labels, 1)
        change = QPushButton("Настроить  ›")
        change.clicked.connect(lambda: self.go(3))
        r.addWidget(change)
        layout.addWidget(route)

        metrics = QHBoxLayout()
        metrics.setSpacing(11)
        self.rx = label("—", name="metric")
        self.tx = label("—", name="metric")
        self.uptime = label("00:00:00", name="metric")
        for title, widget in (("↓  ПОЛУЧЕНО", self.rx),
                              ("↑  ОТПРАВЛЕНО", self.tx),
                              ("◷  ВРЕМЯ", self.uptime)):
            box = card()
            l = QVBoxLayout(box)
            l.setContentsMargins(16, 13, 16, 13)
            l.addWidget(label(title, muted=True))
            l.addWidget(widget)
            metrics.addWidget(box)
        layout.addLayout(metrics)

        server = card()
        sl = QHBoxLayout(server)
        sl.setContentsMargins(20, 13, 18, 13)
        heading = QVBoxLayout()
        heading.addWidget(label("АКТИВНЫЙ СЕРВЕР", muted=True))
        self.profile_label = label("Выберите профиль", size=15)
        self.endpoint_label = label("Конфигурация не загружена", muted=True)
        heading.addWidget(self.profile_label)
        heading.addWidget(self.endpoint_label)
        sl.addLayout(heading, 1)
        select = QPushButton("Изменить  ›")
        select.clicked.connect(lambda: self.go(1))
        sl.addWidget(select)
        layout.addWidget(server)
        bottom = QHBoxLayout()
        import_btn = QPushButton("＋  Импорт")
        import_btn.clicked.connect(self.import_profile)
        bottom.addWidget(import_btn)
        diag_btn = QPushButton("Статистика")
        diag_btn.clicked.connect(lambda: self.go(2))
        bottom.addWidget(diag_btn)
        layout.addLayout(bottom)
        self.pages.addWidget(page)

    def _build_profiles(self):
        page, layout = self._page("Серверы и профили", "У каждого устройства должен быть собственный ключ.")
        self.profile_list = QListWidget()
        self.profile_list.itemDoubleClicked.connect(lambda _item: self.select_profile())
        layout.addWidget(self.profile_list, 1)
        buttons = QHBoxLayout()
        for text_, action in (("Выбрать", self.select_profile),
                              ("＋ Импорт", self.import_profile),
                              ("Удалить", self.delete_profile)):
            button = QPushButton(text_)
            button.clicked.connect(action)
            buttons.addWidget(button)
        layout.addLayout(buttons)
        self.pages.addWidget(page)

    def _build_statistics(self):
        page, layout = self._page("Статистика и диагностика", "Данные VPN-интерфейса и системной службы.")
        self.stat_label = label("Нет данных", size=13)
        layout.addWidget(self.stat_label)
        self.logs = QPlainTextEdit()
        self.logs.setReadOnly(True)
        layout.addWidget(self.logs, 1)
        refresh = QPushButton("Обновить диагностику")
        refresh.clicked.connect(self.diagnose)
        layout.addWidget(refresh)
        self.pages.addWidget(page)

    def _build_settings(self):
        page, layout = self._page("Настройки", "Параметры хранятся отдельно от VPN-профиля.")
        layout.addWidget(label("Маршрутизация", size=16))
        self.mode_box = QComboBox()
        self.mode_box.addItem("Весь IPv4 через VPN", "full")
        self.mode_box.addItem("Только VPN-сервер", "split")
        layout.addWidget(self.mode_box)
        layout.addWidget(label("DNS для полного туннеля", muted=True))
        self.dns_edit = QLineEdit("1.1.1.1")
        layout.addWidget(self.dns_edit)
        self.auto_check = QCheckBox("Подключать автоматически при запуске Linux")
        layout.addWidget(self.auto_check)
        save = QPushButton("Сохранить настройки")
        save.setObjectName("primary")
        save.clicked.connect(self.save_settings)
        layout.addWidget(save)
        layout.addWidget(label("IPv6 в полном туннеле пока блокируется.", muted=True))
        layout.addStretch()
        self.pages.addWidget(page)

    def _build_about(self):
        page, layout = self._page("О NetherVPN", "Ваш собственный экспериментальный VPN.")
        layout.addWidget(label("Noise IK · IPv4 · Linux", size=16))
        layout.addWidget(label("NetherVPN не проходил независимый аудит безопасности.", muted=True))
        layout.addStretch()
        self.pages.addWidget(page)

    def go(self, index):
        self.pages.setCurrentIndex(index)
        for btn, page in self.nav_buttons:
            btn.setProperty("active", page == index)
            btn.style().unpolish(btn)
            btn.style().polish(btn)
        if index == 2:
            self.diagnose()

    def notice(self, message):
        self.events.append(time.strftime("%H:%M:%S") + "  " + message)
        self.events = self.events[-150:]
        self.logs.setPlainText("\n".join(self.events))
        self.logs.verticalScrollBar().setValue(self.logs.verticalScrollBar().maximum())

    def refresh_profiles(self):
        previous = self.current
        self.profiles = sorted(p for p in BASE.glob("*.conf") if p.is_file() and not p.is_symlink())
        self.profile_list.clear()
        for p in self.profiles:
            self.profile_list.addItem(p.stem)
        if previous not in self.profiles:
            self.current = self.profiles[0] if self.profiles else None
        if self.current:
            self.profile_list.setCurrentRow(self.profiles.index(self.current))
        self.load_selected()

    def load_selected(self):
        if not self.current:
            self.profile_label.setText("Добавить сервер")
            self.endpoint_label.setText("Импортируйте .conf")
            return
        try:
            endpoint, _iface, full_legacy, dns_legacy = profile_meta(self.current)
            pref = BASE / (self.current.stem + ".prefs")
            if pref.is_symlink():
                raise ValueError("Символическая ссылка настроек запрещена")
            mode, dns, auto = decode(pref.read_text() if pref.exists() else "",
                                      legacy_mode="full" if full_legacy else "split",
                                      legacy_dns=dns_legacy)
            self.mode, self.dns_value, self.autostart = mode, dns, auto
            self.mode_box.setCurrentIndex(0 if mode == "full" else 1)
            self.dns_edit.setText(dns)
            self.auto_check.setChecked(auto)
            self.profile_label.setText(self.current.stem)
            self.endpoint_label.setText(endpoint)
            self.mode_label.setText("Полный туннель · весь IPv4" if mode == "full" else "Только VPN-сервер")
        except (OSError, ValueError, configparser.Error) as err:
            self.notice("Ошибка настроек: " + str(err))

    def select_profile(self):
        row = self.profile_list.currentRow()
        if 0 <= row < len(self.profiles):
            if self.service_running():
                QMessageBox.warning(self, "NetherVPN", "Отключите VPN перед сменой профиля.")
                return
            self.current = self.profiles[row]
            self.load_selected()
            self.go(0)

    def import_profile(self):
        filename, _ = QFileDialog.getOpenFileName(self, "Импорт профиля", "", "NetherVPN (*.conf)")
        if not filename:
            return
        if self.service_running():
            QMessageBox.warning(self, "NetherVPN", "Отключите VPN перед импортом.")
            return
        src = Path(filename)
        dst = BASE / src.name
        if dst.is_symlink():
            QMessageBox.warning(self, "NetherVPN", "Символические ссылки запрещены.")
            return
        if dst.exists() and QMessageBox.question(self, "Заменить профиль", "Перезаписать " + src.name + "?") != QMessageBox.StandardButton.Yes:
            return
        try:
            raw = src.read_bytes()
            if len(raw) > 65536:
                raise ValueError("Слишком большой профиль")
            fd, temp = tempfile.mkstemp(prefix=".import-", dir=BASE)
            try:
                os.fchmod(fd, 0o600)
                with os.fdopen(fd, "wb") as out:
                    out.write(raw)
                    out.flush()
                    os.fsync(out.fileno())
                os.replace(temp, dst)
            finally:
                if os.path.exists(temp):
                    os.unlink(temp)
            self.current = dst
            self.refresh_profiles()
            self.go(0)
        except (OSError, ValueError) as err:
            QMessageBox.critical(self, "Импорт", str(err))

    def delete_profile(self):
        if self.service_running():
            QMessageBox.warning(self, "NetherVPN", "Сначала отключите VPN.")
            return
        row = self.profile_list.currentRow()
        if not 0 <= row < len(self.profiles):
            return
        p = self.profiles[row]
        if QMessageBox.question(self, "Удалить профиль", "Удалить " + p.stem + "?") != QMessageBox.StandardButton.Yes:
            return
        p.unlink()
        (BASE / (p.stem + ".prefs")).unlink(missing_ok=True)
        self.current = None
        self.refresh_profiles()

    def save_settings(self, notify=True):
        if not self.current:
            QMessageBox.warning(self, "NetherVPN", "Сначала выберите профиль.")
            return False
        try:
            mode = self.mode_box.currentData()
            content = encode(mode, self.dns_edit.text(), self.auto_check.isChecked())
            pref = BASE / (self.current.stem + ".prefs")
            if pref.is_symlink():
                raise ValueError("Символическая ссылка настроек запрещена")
            fd, temp = tempfile.mkstemp(prefix=".prefs-", dir=BASE)
            try:
                os.fchmod(fd, 0o600)
                with os.fdopen(fd, "w", encoding="utf-8") as out:
                    out.write(content)
                    out.flush()
                    os.fsync(out.fileno())
                os.replace(temp, pref)
            finally:
                if os.path.exists(temp):
                    os.unlink(temp)
            self.mode = mode
            self.mode_label.setText("Полный туннель · весь IPv4" if mode == "full" else "Только VPN-сервер")
            if notify:
                self.notice("Настройки сохранены")
                QMessageBox.information(self, "NetherVPN", "Настройки применятся при следующем подключении.")
            return True
        except (OSError, ValueError) as err:
            QMessageBox.critical(self, "Настройки", str(err))
            return False

    def service_running(self):
        try:
            p = subprocess.run(["systemctl", "is-active", "--quiet", "nethervpn-client.service"],
                               timeout=2, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL)
            return p.returncode == 0
        except (OSError, subprocess.TimeoutExpired):
            return False

    def toggle(self):
        if self.busy:
            return
        if self.service_running():
            self.operate(["disconnect"])
            return
        if not self.current:
            self.go(1)
            QMessageBox.information(self, "NetherVPN", "Импортируйте профиль.")
            return
        if not Path(HELPER).is_file() or not shutil.which("pkexec"):
            QMessageBox.warning(self, "NetherVPN", "Установите desktop helper: bash scripts/install-desktop.sh")
            return
        if not self.save_settings(notify=False):
            return
        if self.mode == "full":
            if QMessageBox.question(self, "Полный туннель",
                 "IPv4 и DNS направятся через VPN. IPv6 будет блокироваться. "
                 "При разрыве соединения интернет может стать недоступен. Продолжить?",
                 QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
                 QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes:
                return
        self.operate(["connect", self.current.name])

    def operate(self, args):
        self.busy = True
        self.ring.state("Подключение…", "Управление службой", AMBER)
        self.worker = PrivilegedWorker(args)
        self.worker.finished_operation.connect(self.operation_done)
        self.worker.start()

    def operation_done(self, ok, detail):
        self.busy = False
        if detail:
            self.notice(detail)
        if not ok:
            QMessageBox.critical(self, "NetherVPN", detail or "Не удалось управлять службой")
        elif self.worker.args[0] == "connect":
            self.started_at = time.monotonic()
        else:
            self.started_at = None
            self.last_counters = None
        self.tick()

    def tick(self):
        running = self.service_running()
        tun_name = "nvpn0"
        if self.current:
            try:
                tun_name = profile_meta(self.current)[1]
            except (OSError, configparser.Error):
                pass
        tun_path = Path("/sys/class/net") / tun_name / "statistics"
        iface = running and tun_path.exists()
        if self.busy:
            title, subtitle, color = "Подключение…", "Управление службой", AMBER
        elif iface:
            title, subtitle, color = "Отключить", "VPN-интерфейс работает", MINT
        elif running:
            title, subtitle, color = "Отключить", "Ожидание туннеля", AMBER
        else:
            title, subtitle, color = "Подключить", "Нажмите для включения", MUTED
        self.ring.state(title, subtitle, color)
        self.status.setText("●  " + ("VPN-интерфейс активен" if iface else "Служба запущена" if running else "Не подключено"))
        self.hint.setText("Трафик может проходить через VPN" if iface else "Проверьте подключение" if running else "Выберите сервер и подключитесь")
        self.side_status.setText("●  Служба активна" if running else "●  Служба остановлена")
        if iface:
            try:
                rx = int((tun_path/"rx_bytes").read_text())
                tx = int((tun_path/"tx_bytes").read_text())
                self.rx.setText(self.human(rx))
                self.tx.setText(self.human(tx))
            except (OSError, ValueError):
                pass
        else:
            self.rx.setText("—")
            self.tx.setText("—")
        secs = int(time.monotonic() - self.started_at) if running and self.started_at else 0
        self.uptime.setText(f"{secs//3600:02d}:{secs//60%60:02d}:{secs%60:02d}")
        self.stat_label.setText(f"Служба: {'активна' if running else 'остановлена'}\n"
                                f"Интерфейс: {tun_name} · {'есть' if iface else 'нет'}\n"
                                f"Трафик: ↓ {self.rx.text()} · ↑ {self.tx.text()}")

    @staticmethod
    def human(n):
        for unit in ("Б", "КиБ", "МиБ", "ГиБ"):
            if n < 1024 or unit == "ГиБ":
                return f"{n:.1f} {unit}" if unit != "Б" else f"{int(n)} Б"
            n /= 1024

    def diagnose(self):
        for cmd in (["systemctl", "show", "nethervpn-client.service",
                     "--property=ActiveState", "--property=SubState"],
                    ["journalctl", "-u", "nethervpn-client.service", "-n", "25",
                     "--no-pager", "-o", "cat"]):
            try:
                p = subprocess.run(cmd, capture_output=True, text=True, timeout=3)
                self.notice((p.stdout or p.stderr).strip()[:4000])
            except (OSError, subprocess.TimeoutExpired) as err:
                self.notice(str(err))

    def closeEvent(self, event):
        if self.busy:
            event.ignore()
            return
        # Closing the UI never kills the active VPN system service.
        event.accept()

def main():
    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    app.setStyleSheet(STYLE)
    window = Window()
    window.show()
    sys.exit(app.exec())

if __name__ == "__main__":
    main()
