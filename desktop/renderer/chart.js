'use strict';

/**
 * Подробный график: шкала значений и времени, точка на каждой проверке,
 * наведение курсора показывает точное значение, время и прирост.
 */
class MinerChart {
  constructor(canvas) {
    this.c = canvas;
    this.g = canvas.getContext('2d');
    this.all = [];
    this.vis = [];
    this.sel = -1;
    this.color = '#3FD08A';
    this.dMin = 0;
    this.dMax = 0;
    this.yLo = 0;
    this.yHi = 1;
    this.plot = { l: 0, t: 0, r: 0, b: 0 };

    new ResizeObserver(() => this.draw()).observe(canvas);
    canvas.addEventListener('pointermove', (e) => this.hover(e));
    canvas.addEventListener('pointerdown', (e) => this.hover(e));
    canvas.addEventListener('pointerleave', () => {
      if (this.sel !== -1) { this.sel = -1; this.draw(); }
    });
  }

  /**
   * @param points [[время, значение], ...] по возрастанию времени
   * @param from начало периода или null для всей истории
   */
  setData(points, color, from) {
    this.all = points;
    this.color = color;
    this.sel = -1;
    if (!points.length) {
      this.vis = [];
    } else if (from == null) {
      this.vis = points;
      this.dMin = points[0][0];
    } else {
      // последняя точка до начала периода нужна, чтобы линия начиналась с левого края
      let prev = -1;
      for (let i = 0; i < points.length; i++) if (points[i][0] <= from) prev = i;
      this.vis = prev >= 0 ? points.slice(prev) : points;
      this.dMin = prev >= 0 ? from : points[0][0];
    }
    if (this.vis.length) this.dMax = this.vis[this.vis.length - 1][0];
    this.draw();
  }

  css(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  x(t) {
    const p = this.plot;
    return p.l + ((t - this.dMin) / (this.dMax - this.dMin)) * (p.r - p.l);
  }

  y(v) {
    const p = this.plot;
    return p.b - ((v - this.yLo) / (this.yHi - this.yLo)) * (p.b - p.t);
  }

  draw() {
    const c = this.c;
    const dpr = window.devicePixelRatio || 1;
    const w = c.clientWidth;
    const h = c.clientHeight;
    if (!w || !h) return;
    if (c.width !== Math.round(w * dpr) || c.height !== Math.round(h * dpr)) {
      c.width = Math.round(w * dpr);
      c.height = Math.round(h * dpr);
    }
    const g = this.g;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);

    const col = {
      muted: this.css('--muted'), line: this.css('--line'), card: this.css('--card'),
      text: this.css('--text'), bg: this.css('--bg'), accent: this.css('--accent'),
      font: this.css('--font-ui'),
    };
    const vis = this.vis;
    const inRange = vis.filter((p) => p[0] >= this.dMin).length;

    if (vis.length < 2 || !(this.dMax > this.dMin) || inRange < 1) {
      g.fillStyle = col.muted;
      g.textAlign = 'center';
      g.textBaseline = 'middle';
      g.font = `14px ${col.font}`;
      g.fillText(
        this.all.length < 2 ? 'График появится после второй проверки' : 'За этот период пока мало проверок',
        w / 2, h / 2
      );
      return;
    }

    // ── шкала значений с «круглыми» делениями
    let vMin = Infinity, vMax = -Infinity;
    for (const p of vis) { vMin = Math.min(vMin, p[1]); vMax = Math.max(vMax, p[1]); }
    const raw = vMax - vMin < 1 ? Math.max(1, vMax * 0.01) : vMax - vMin;
    const step = niceStep(raw / 4);
    let lo = Math.floor(vMin / step) * step;
    let hi = Math.ceil(vMax / step) * step;
    if (hi - lo < step) hi = lo + step;
    if (lo < 0) lo = 0;
    this.yLo = lo;
    this.yHi = hi;

    const ticks = [];
    for (let t = lo; t <= hi + step / 2; t += step) ticks.push(t);
    g.font = `11px ${col.font}`;
    const labels = ticks.map((t) => Fmt.compact(Math.round(t)));
    const labelW = Math.max(...labels.map((s) => g.measureText(s).width));
    this.plot = { l: labelW + 12, t: 12, r: w - 10, b: h - 26 };
    const p = this.plot;

    g.strokeStyle = col.line;
    g.lineWidth = 1;
    g.fillStyle = col.muted;
    g.textAlign = 'right';
    g.textBaseline = 'middle';
    ticks.forEach((t, i) => {
      const gy = Math.round(this.y(t)) + 0.5;
      g.beginPath(); g.moveTo(p.l, gy); g.lineTo(p.r, gy); g.stroke();
      g.fillText(labels[i], p.l - 8, gy);
    });

    // ── шкала времени
    const span = this.dMax - this.dMin;
    const fmt = span < 36 * HOUR ? Fmt.hm : span < 4 * DAY ? Fmt.dayHm : Fmt.day;
    const n = w < 520 ? 3 : 5;
    g.textBaseline = 'alphabetic';
    for (let i = 0; i <= n; i++) {
      const tt = this.dMin + (span * i) / n;
      const gx = Math.round(this.x(tt)) + 0.5;
      g.beginPath(); g.moveTo(gx, p.b); g.lineTo(gx, p.b + 4); g.stroke();
      g.textAlign = i === 0 ? 'left' : i === n ? 'right' : 'center';
      g.fillText(fmt(tt), gx, h - 7);
    }

    // ── линия и заливка
    g.save();
    g.beginPath();
    g.rect(p.l, p.t - 8, p.r - p.l + 8, p.b - p.t + 8);
    g.clip();
    const path = new Path2D();
    vis.forEach(([t, v], i) => {
      const X = this.x(t), Y = this.y(v);
      if (i) path.lineTo(X, Y); else path.moveTo(X, Y);
    });
    const area = new Path2D(path);
    area.lineTo(this.x(vis[vis.length - 1][0]), p.b);
    area.lineTo(this.x(vis[0][0]), p.b);
    area.closePath();
    const grad = g.createLinearGradient(0, p.t, 0, p.b);
    grad.addColorStop(0, rgba(this.color, 0.32));
    grad.addColorStop(1, rgba(this.color, 0));
    g.fillStyle = grad;
    g.fill(area);
    g.strokeStyle = this.color;
    g.lineWidth = 2.2;
    g.lineJoin = 'round';
    g.lineCap = 'round';
    g.stroke(path);

    const dots = vis.filter((q) => q[0] >= this.dMin);
    if (dots.length <= 120) {
      for (const [t, v] of dots) dot(g, this.x(t), this.y(v), 3.6, 2.4, col.card, this.color);
    }
    g.restore();

    const last = vis[vis.length - 1];
    dot(g, this.x(last[0]), this.y(last[1]), 6, 4.2, col.card, this.color);

    if (this.sel >= 0 && this.sel < vis.length) this.tooltip(g, w, h, col);
  }

  hover(e) {
    if (this.vis.length < 2) return;
    const r = this.c.getBoundingClientRect();
    const mx = e.clientX - r.left;
    let best = -1, bestDist = Infinity;
    this.vis.forEach(([t], i) => {
      if (t < this.dMin) return;
      const d = Math.abs(this.x(t) - mx);
      if (d < bestDist) { bestDist = d; best = i; }
    });
    if (best !== this.sel) { this.sel = best; this.draw(); }
  }

  tooltip(g, w, h, col) {
    const i = this.sel;
    const [t, v] = this.vis[i];
    const px = this.x(t), py = this.y(v);
    const p = this.plot;

    g.save();
    g.setLineDash([4, 4]);
    g.strokeStyle = col.muted;
    g.lineWidth = 1;
    g.beginPath(); g.moveTo(px, p.t); g.lineTo(px, p.b); g.stroke();
    g.restore();
    dot(g, px, py, 7, 5, col.card, this.color);

    const delta = i > 0 ? v - this.vis[i - 1][1] : null;
    const l1 = Fmt.num(v);
    const l2 = Fmt.full(t);
    const l3 = delta == null ? null : `${Fmt.delta(delta)} с прошлой проверки`;

    g.font = `600 15px ${col.font}`;
    const w1 = g.measureText(l1).width;
    g.font = `12px ${col.font}`;
    const w2 = g.measureText(l2).width;
    g.font = `600 12px ${col.font}`;
    const w3 = l3 ? g.measureText(l3).width : 0;

    const pad = 10;
    const bw = Math.max(w1, w2, w3) + pad * 2;
    const bh = pad * 2 + 18 + 14 + (l3 ? 16 : 0);
    let left = px + 14;
    if (left + bw > w - 2) left = px - 14 - bw;
    left = Math.max(2, left);
    let top = py - bh - 12;
    if (top < 2) top = py + 12;
    top = Math.min(top, h - bh - 2);

    g.fillStyle = col.bg;
    g.strokeStyle = col.line;
    g.lineWidth = 1;
    roundRect(g, left, top, bw, bh, 10);
    g.fill();
    g.stroke();

    g.textAlign = 'left';
    g.textBaseline = 'alphabetic';
    let by = top + pad + 14;
    g.fillStyle = col.text; g.font = `600 15px ${col.font}`; g.fillText(l1, left + pad, by);
    by += 16;
    g.fillStyle = col.muted; g.font = `12px ${col.font}`; g.fillText(l2, left + pad, by);
    if (l3) {
      by += 16;
      g.fillStyle = delta > 0 ? col.accent : col.muted;
      g.font = `600 12px ${col.font}`;
      g.fillText(l3, left + pad, by);
    }
  }
}

const HOUR = 3600000;
const DAY = 24 * HOUR;

function niceStep(rough) {
  if (rough <= 0) return 1;
  const mag = 10 ** Math.floor(Math.log10(rough));
  const n = rough / mag;
  const nice = n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10;
  return Math.max(1, nice * mag);
}

function dot(g, x, y, rOuter, rInner, ring, fill) {
  g.beginPath(); g.arc(x, y, rOuter, 0, Math.PI * 2); g.fillStyle = ring; g.fill();
  g.beginPath(); g.arc(x, y, rInner, 0, Math.PI * 2); g.fillStyle = fill; g.fill();
}

function roundRect(g, x, y, w, h, r) {
  g.beginPath();
  g.moveTo(x + r, y);
  g.arcTo(x + w, y, x + w, y + h, r);
  g.arcTo(x + w, y + h, x, y + h, r);
  g.arcTo(x, y + h, x, y, r);
  g.arcTo(x, y, x + w, y, r);
  g.closePath();
}

function rgba(hex, a) {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
}
