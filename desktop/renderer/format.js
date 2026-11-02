'use strict';

const Fmt = (() => {
  const nf = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 });
  const hm = new Intl.DateTimeFormat('ru-RU', { hour: '2-digit', minute: '2-digit' });
  const day = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short' });
  const dayHm = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  const full = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' });

  const num = (v) => nf.format(v).replace(/ | /g, ' ');

  function compact(v) {
    const a = Math.abs(v);
    const f = (x, suffix) => {
      const digits = Math.abs(x) >= 100 ? 0 : Math.abs(x) >= 10 ? 1 : 2;
      return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: digits }).format(x) + ' ' + suffix;
    };
    if (a >= 1e9) return f(v / 1e9, 'млрд');
    if (a >= 1e6) return f(v / 1e6, 'млн');
    if (a >= 1e4) return f(v / 1e3, 'тыс.');
    return num(v);
  }

  return {
    num,
    compact,
    delta: (v) => (v > 0 ? '+' + num(v) : v < 0 ? '−' + num(-v) : '0'),
    hm: (t) => hm.format(t),
    day: (t) => day.format(t),
    dayHm: (t) => dayHm.format(t),
    full: (t) => full.format(t),
  };
})();
