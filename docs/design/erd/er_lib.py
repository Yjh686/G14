# -*- coding: utf-8 -*-
"""ER 图渲染：HTML(绝对定位) + SVG(鸦爪连线) -> Chrome headless 截图 PNG。"""
import math
import subprocess
from pathlib import Path

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"


class Theme:
    def __init__(self, fs=17.0):
        self.fs = fs
        self.row_h = round(fs * 1.62)
        self.hd_h = round(fs * 2.05)
        self.hd_h2 = round(fs * 2.85)
        self.ft_h = round(fs * 1.35)
        self.padx = round(fs * 0.55)
        self.kw = round(fs * 1.75)          # 键列宽度
        self.title_fs = fs + 1.5
        self.sub_fs = fs - 3.5
        self.row_fs = fs - 1.0
        self.type_fs = fs - 3.5
        self.k_fs = fs - 4.5
        self.ft_fs = fs - 3.0

    def css(self):
        t = self
        return f"""
* {{ box-sizing: border-box; }}
html, body {{ margin:0; padding:0; background:#ffffff; }}
body {{ font-family:"Microsoft YaHei","Segoe UI",Arial,sans-serif; }}
#canvas {{ position:relative; background:#fff; }}
#edges {{ position:absolute; left:0; top:0; z-index:1; }}
.box {{ position:absolute; z-index:2; border:1px solid #6f8296; border-radius:3px; background:#fff; overflow:hidden;
        box-shadow:0 1px 2px rgba(15,30,50,.12); }}
.box .hd {{ color:#fff; padding:{round(t.fs*0.12)}px {t.padx}px; }}
.box .hd .t {{ font-size:{t.title_fs}px; font-weight:700; line-height:{round(t.fs*1.25)}px; white-space:nowrap; }}
.box .hd .s {{ font-size:{t.sub_fs}px; line-height:{round(t.fs*1.0)}px; opacity:.95; white-space:nowrap; }}
.box .rw {{ display:flex; align-items:center; height:{t.row_h}px; padding:0 {t.padx}px; font-size:{t.row_fs}px;
            color:#22303d; border-top:1px solid #eef1f4; white-space:nowrap; }}
.box .rw .k {{ width:{t.kw}px; font-size:{t.k_fs}px; font-weight:700; color:#fff; text-align:center;
               border-radius:2px; margin-right:{round(t.fs*0.45)}px; line-height:{round(t.fs*0.8)}px;
               height:{round(t.fs*0.8)}px; flex:none; }}
.box .rw .k.pk {{ background:#a8760f; }}
.box .rw .k.fk {{ background:#2b6a99; }}
.box .rw .k.uk {{ background:#5d8f34; }}
.box .rw .k.gc {{ background:#7d5aa0; }}
.box .rw .n {{ flex:1; }}
.box .rw.pkrow .n {{ font-weight:700; }}
.box .rw .ty {{ font-size:{t.type_fs}px; color:#75838f; margin-left:{round(t.fs*0.5)}px; }}
.box .ft {{ height:{t.ft_h}px; line-height:{t.ft_h}px; padding:0 {t.padx}px; font-size:{t.ft_fs}px; color:#8b98a5;
            border-top:1px solid #eef1f4; background:#fbfcfd; white-space:nowrap; }}
.box.stub {{ border-style:dashed; }}
.box.stub .hd {{ opacity:.85; }}
.frame {{ position:absolute; z-index:0; border:1px solid #c3cfdb; border-radius:5px; background:#f9fbfd; }}
.frame .cap {{ position:absolute; top:-{round(t.fs*0.62)}px; left:{round(t.fs*0.6)}px; background:#fff;
               padding:0 6px; font-size:{t.fs-3}px; font-weight:700; color:#3d5570; letter-spacing:.5px; }}
.legend {{ position:absolute; z-index:3; font-size:{t.fs-4}px; color:#33414f;
           border:1px solid #d6dee6; border-radius:4px; background:#fff; padding:{round(t.fs*0.5)}px {round(t.fs*0.8)}px; }}
.legend .l {{ display:flex; align-items:center; height:{round(t.fs*1.35)}px; gap:6px; white-space:nowrap; }}
"""


class Box:
    def __init__(self, name, x, y, w, rows, title, sub="", color="#3c6e9e", stub=False, footer=None):
        self.name, self.x, self.y, self.w = name, x, y, w
        self.rows, self.title, self.sub, self.color = rows, title, sub, color
        self.stub, self.footer = stub, footer
        self._t = None
        self.hd_h = 0
        self.h = 0

    def measure(self, t):
        self._t = t
        self.hd_h = t.hd_h2 if self.sub else t.hd_h
        self.h = self.hd_h + len(self.rows) * t.row_h + (t.ft_h if self.footer is not None else 0)

    def row_y(self, i):
        return self.y + self.hd_h + i * self._t.row_h + self._t.row_h / 2

    def find(self, col):
        for i, r in enumerate(self.rows):
            if r[1] == col:
                return i
        raise KeyError("%s.%s" % (self.name, col))

    def left(self, col=None, dy=0):
        return (self.x, self.row_y(self.find(col)) if col else self.y + self.h / 2 + dy)

    def right(self, col=None, dy=0):
        return (self.x + self.w, self.row_y(self.find(col)) if col else self.y + self.h / 2 + dy)

    def top(self, col=None):
        return (self.x + self.w / 2, self.y)

    def bottom(self, col=None):
        return (self.x + self.w / 2, self.y + self.h)


class Diagram:
    def __init__(self, width, height, fs=17.0, scale=2, bg="#ffffff", paint_bg=False):
        self.w, self.h, self.scale, self.bg = width, height, scale, bg
        self.paint_bg = paint_bg
        self.t = Theme(fs)
        self.boxes, self.conns, self.frames, self.legends = [], [], [], []

    def box(self, *a, **kw):
        b = Box(*a, **kw)
        b.measure(self.t)          # 立即量算，便于随后用 row_y/left/right 取锚点
        self.boxes.append(b)
        return b

    def frame(self, x, y, w, h, cap):
        self.frames.append((x, y, w, h, cap))

    def frame_around(self, boxes, cap, pad=18, pad_top=30, pad_bottom=16):
        """由若干 Box 的包围盒自动生成分组框，避免手算高度。"""
        x0 = min(b.x for b in boxes) - pad
        y0 = min(b.y for b in boxes) - pad_top
        x1 = max(b.x + b.w for b in boxes) + pad
        y1 = max(b.y + b.h for b in boxes) + pad_bottom
        self.frames.append((x0, y0, x1 - x0, y1 - y0, cap))
        return (x0, y0, x1, y1)

    def fit(self, boxes, pad=0):
        """给画布高度/宽度留出足够空间。"""
        self.w = max(self.w, int(max(b.x + b.w for b in boxes)) + pad)
        self.h = max(self.h, int(max(b.y + b.h for b in boxes)) + pad)

    def conn(self, points, start="many", end="one", dashed=False, badge=None, color="#46596c",
             width=1.25, badge_at=None):
        self.conns.append(dict(points=points, start=start, end=end, dashed=dashed, badge=badge,
                               color=color, width=width, badge_at=badge_at))

    def legend(self, x, y, items):
        self.legends.append((x, y, items))

    # ---------- SVG ----------
    @staticmethod
    def _p(p):
        return "%.1f,%.1f" % (p[0], p[1])

    def _marker(self, end, prev, kind, color):
        dx, dy = end[0] - prev[0], end[1] - prev[1]
        L = math.hypot(dx, dy) or 1
        ux, uy = dx / L, dy / L
        nx, ny = -uy, ux
        k = self.t.fs / 17.0
        out = []
        g = 7.5 * k
        if kind in ("one", "zero_one"):
            if kind == "zero_one":
                c = (end[0] - ux * 16 * k, end[1] - uy * 16 * k)
                out.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="#fff" stroke="%s" stroke-width="1.3"/>'
                           % (c[0], c[1], 3.4 * k, color))
            a = (end[0] + nx * g, end[1] + ny * g)
            b = (end[0] - nx * g, end[1] - ny * g)
            out.append('<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke="%s" stroke-width="1.6"/>'
                       % (a[0], a[1], b[0], b[1], color))
        elif kind == "many":
            base = (end[0] - ux * 14 * k, end[1] - uy * 14 * k)
            for s in (-6.5 * k, 0, 6.5 * k):
                p1 = (end[0] + nx * s, end[1] + ny * s)
                out.append('<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke="%s" stroke-width="1.5"/>'
                           % (base[0], base[1], p1[0], p1[1], color))
        return out

    def svg(self):
        t = self.t
        o = ['<svg id="edges" width="%d" height="%d" viewBox="0 0 %d %d" xmlns="http://www.w3.org/2000/svg">'
             % (self.w, self.h, self.w, self.h)]
        if self.paint_bg:
            # 注意：该矩形位于 z-index:1 的 svg 上，会盖住 z-index:0 的分组框，
            # 因此只在需要纯色底时启用；默认由 body 背景提供白色。
            o.append('<rect x="0" y="0" width="%d" height="%d" fill="%s"/>' % (self.w, self.h, self.bg))
        for c in self.conns:
            pts = c["points"]
            d = "M " + " L ".join(self._p(p) for p in pts)
            dash = ' stroke-dasharray="%d,%d"' % (round(t.fs * 0.4), round(t.fs * 0.32)) if c["dashed"] else ""
            o.append('<path d="%s" fill="none" stroke="%s" stroke-width="%.1f" stroke-linejoin="round"%s/>'
                     % (d, c["color"], c["width"], dash))
            if c["start"]:
                o += self._marker(pts[0], pts[1], c["start"], c["color"])
            if c["end"]:
                o += self._marker(pts[-1], pts[-2], c["end"], c["color"])
            if c["badge"] is not None:
                p = c["badge_at"]
                if p is None:
                    i = len(pts) // 2
                    a, b = pts[max(0, i - 1)], pts[i]
                    p = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
                r = t.fs * 0.52
                o.append('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="#fff" stroke="%s" stroke-width="1"/>'
                         % (p[0], p[1], r, c["color"]))
                o.append('<text x="%.1f" y="%.1f" font-size="%.1f" fill="%s" text-anchor="middle" '
                         'font-family="Microsoft YaHei" dominant-baseline="central">%s</text>'
                         % (p[0], p[1] + 0.5, t.fs * 0.62, c["color"], c["badge"]))
        o.append("</svg>")
        return "\n".join(o)

    def html(self):
        t = self.t
        parts = ['<!DOCTYPE html><html><head><meta charset="utf-8"><style>%s</style></head><body>' % t.css(),
                 '<div id="canvas" style="width:%dpx;height:%dpx">' % (self.w, self.h)]
        for (x, y, w, h, cap) in self.frames:
            parts.append('<div class="frame" style="left:%dpx;top:%dpx;width:%dpx;height:%dpx">'
                         '<span class="cap">%s</span></div>' % (x, y, w, h, cap))
        parts.append(self.svg())
        for b in self.boxes:
            cls = "box stub" if b.stub else "box"
            parts.append('<div class="%s" style="left:%dpx;top:%dpx;width:%dpx;height:%dpx">'
                         % (cls, b.x, b.y, b.w, b.h))
            parts.append('<div class="hd" style="background:%s">' % b.color)
            parts.append('<div class="t">%s</div>' % b.title)
            if b.sub:
                parts.append('<div class="s">%s</div>' % b.sub)
            parts.append("</div>")
            for (k, name, typ) in b.rows:
                kcls = {"PK": "pk", "FK": "fk", "UK": "uk", "UQ": "uk", "GC": "gc"}.get(k or "", "")
                parts.append('<div class="rw%s">' % (" pkrow" if k == "PK" else ""))
                if k:
                    parts.append('<span class="k %s">%s</span>' % (kcls, k))
                else:
                    parts.append('<span style="width:%.0fpx;margin-right:%.0fpx;flex:none;display:inline-block">'
                                 '</span>' % (t.kw, t.fs * 0.45))
                parts.append('<span class="n">%s</span>' % name)
                if typ:
                    parts.append('<span class="ty">%s</span>' % typ)
                parts.append("</div>")
            if b.footer is not None:
                parts.append('<div class="ft">%s</div>' % b.footer)
            parts.append("</div>")
        for (x, y, items) in self.legends:
            parts.append('<div class="legend" style="left:%dpx;top:%dpx">' % (x, y))
            for it in items:
                parts.append('<div class="l">%s</div>' % it)
            parts.append("</div>")
        parts.append("</div></body></html>")
        return "\n".join(parts)

    def render(self, png_path, html_path=None, slack=160):
        """Chrome 的实际可视高度比 --window-size 略小（实测约少 100px），
        会裁掉画布最底部的内容。因此按 window-size 加大 slack 出图，再裁到位。"""
        for b in self.boxes:
            b.measure(self.t)
        png_path = Path(png_path)
        html_path = Path(html_path) if html_path else png_path.with_suffix(".html")
        html_path.write_text(self.html(), encoding="utf-8")
        cmd = [CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
               "--force-device-scale-factor=%s" % self.scale,
               "--default-background-color=ffffffff",
               "--window-size=%d,%d" % (self.w, self.h + slack),
               "--screenshot=%s" % str(png_path), html_path.as_uri()]
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
        if not png_path.exists():
            raise RuntimeError("render failed\n%s\n%s" % (r.stdout, r.stderr))
        # 裁到设计尺寸，保证四边留白一致
        try:
            from PIL import Image
            im = Image.open(png_path)
            want = (int(self.w * self.scale), int(self.h * self.scale))
            if im.size != want:
                im.crop((0, 0, min(im.width, want[0]), min(im.height, want[1]))).save(png_path)
        except Exception as e:      # 裁剪失败不影响出图
            print("crop warn:", e)
        return png_path


def bad(b):
    return '<span style="display:inline-block;width:9px;height:9px;border-radius:2px;background:%s"></span>' % b
