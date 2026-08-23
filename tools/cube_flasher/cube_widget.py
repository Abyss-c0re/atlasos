# Crimson lattice — cube_gl spike #FF141A
import math
from PyQt5.QtCore import QTimer, Qt
from PyQt5.QtGui import QBrush, QColor, QPainter, QPen, QRadialGradient
from PyQt5.QtWidgets import QWidget

SPIKE = QColor(255, 20, 26)
CAGE = QColor(140, 5, 13, 160)
VOID = QColor(6, 0, 1)


class CrimsonCube(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setMinimumSize(280, 280)
        self.yaw = 0.6
        self.pitch = 0.42
        self.t = 0.0
        self.glow = 0.15
        self.spin_dps = 28.0
        self._n = 8
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._tick)
        self._timer.start(16)

    def set_phase(self, phase, glow=None):
        if glow is not None:
            self.glow = max(0.0, min(1.0, float(glow)))
        self.spin_dps = {
            "idle": 16.0,
            "build": 28.0,
            "connect": 18.0,
            "write": 12.0,
            "done": 8.0,
            "fail": 6.0,
        }.get(phase, 20.0)

    def _tick(self):
        self.t += 0.016
        self.yaw += math.radians(self.spin_dps) * 0.016
        self.update()

    def _project(self, x, y, z, w, h):
        y = y + 0.06 * math.sin(self.t * 1.4)
        cy, sy = math.cos(self.yaw), math.sin(self.yaw)
        cp, sp = math.cos(self.pitch), math.sin(self.pitch)
        x1 = x * cy - z * sy
        z1 = x * sy + z * cy
        y2 = y * cp - z1 * sp
        z2 = y * sp + z1 * cp
        dist = 3.6
        f = dist / (dist + z2 + 2.2)
        px = w * 0.5 + x1 * f * (min(w, h) * 0.38)
        py = h * 0.52 - y2 * f * (min(w, h) * 0.38)
        return px, py, f, z2

    def paintEvent(self, ev):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.fillRect(self.rect(), VOID)
        w, h = self.width(), self.height()
        g = self.glow
        if g > 0.2:
            rad = min(w, h) * (0.28 + 0.22 * g)
            grad = QRadialGradient(w * 0.5, h * 0.5, rad)
            a = int(40 + 140 * g)
            grad.setColorAt(0.0, QColor(255, 20, 26, a))
            grad.setColorAt(0.55, QColor(180, 8, 16, int(a * 0.35)))
            grad.setColorAt(1.0, QColor(0, 0, 0, 0))
            p.fillRect(self.rect(), QBrush(grad))
        n = self._n
        step = 2.0 / (n - 1)
        pts = []
        for iz in range(n):
            for iy in range(n):
                for ix in range(n):
                    if not (ix in (0, n - 1) or iy in (0, n - 1) or iz in (0, n - 1)):
                        continue
                    x = -1.0 + ix * step
                    y = -1.0 + iy * step
                    z = -1.0 + iz * step
                    px, py, f, z2 = self._project(x, y, z, w, h)
                    edge = (ix in (0, n - 1)) + (iy in (0, n - 1)) + (iz in (0, n - 1))
                    pts.append((z2, px, py, f, edge))
        pts.sort(key=lambda t: t[0])
        corners = [
            (-1, -1, -1), (1, -1, -1), (1, 1, -1), (-1, 1, -1),
            (-1, -1, 1), (1, -1, 1), (1, 1, 1), (-1, 1, 1),
        ]
        edges = [
            (0, 1), (1, 2), (2, 3), (3, 0),
            (4, 5), (5, 6), (6, 7), (7, 4),
            (0, 4), (1, 5), (2, 6), (3, 7),
        ]
        pc = [self._project(x, y, z, w, h)[:2] for x, y, z in corners]
        pen = QPen(CAGE)
        pen.setWidthF(1.4 + 2.2 * g)
        p.setPen(pen)
        for a, b in edges:
            p.drawLine(int(pc[a][0]), int(pc[a][1]), int(pc[b][0]), int(pc[b][1]))
        pulse = 0.5 + 0.5 * math.sin(self.t * 3.2)
        for z2, px, py, f, edge in pts:
            hot = edge >= 2
            size = (2.0 + (3.5 if hot else 1.2) * f) * (1.0 + 0.9 * g)
            if hot:
                size *= 1.0 + 0.35 * pulse * g
            col = QColor(SPIKE)
            col.setAlpha(int((70 + 160 * g if hot else 35 + 80 * g) * f))
            p.setPen(Qt.NoPen)
            p.setBrush(col)
            p.drawEllipse(int(px - size), int(py - size), int(size * 2), int(size * 2))
        p.end()
