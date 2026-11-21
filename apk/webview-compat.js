/*
 * 老 WebView 兼容补丁：在应用脚本之前执行（由 scripts/build-apk.mjs 注入 index.html）。
 * 只补语言/布局能力，不改业务逻辑；图片尺寸检测的超时兜底在应用源码里，这里不接管 decode()。
 */
(function () {
  try {
    if (!Array.prototype.toSorted) Array.prototype.toSorted = function (c) { return this.slice().sort(c); };
    if (!Array.prototype.toReversed) Array.prototype.toReversed = function () { return this.slice().reverse(); };
    if (!Array.prototype.toSpliced) Array.prototype.toSpliced = function () { var a = this.slice(); return a.splice.apply(a, arguments), a; };
    if (!Array.prototype.with) Array.prototype.with = function (i, v) { var a = this.slice(); a[i < 0 ? a.length + i : i] = v; return a; };
    if (!Array.prototype.at) Array.prototype.at = function (i) { i = i || 0; return i < 0 ? this[this.length + i] : this[i]; };
    if (!Array.prototype.findLast) Array.prototype.findLast = function (f) { for (var i = this.length - 1; i >= 0; i--) if (f(this[i], i, this)) return this[i]; };
    if (!Array.prototype.findLastIndex) Array.prototype.findLastIndex = function (f) { for (var i = this.length - 1; i >= 0; i--) if (f(this[i], i, this)) return i; return -1; };
    if (!Object.hasOwn) Object.hasOwn = function (o, k) { return Object.prototype.hasOwnProperty.call(o, k); };
    if (!Object.groupBy) Object.groupBy = function (a, f) { var o = Object.create(null); a.forEach(function (x) { var k = f(x); (o[k] || (o[k] = [])).push(x); }); return o; };
    if (typeof CSS === 'undefined') window.CSS = {};
    if (!CSS.escape) CSS.escape = function (s) { return String(s).replace(/([^a-zA-Z0-9_-])/g, '\\$1'); };
  } catch (e) {}

  /* 旧 WebView 不认 dvh：主框架高度会塌成 0，整页白屏，这里退回 vh */
  try {
    var style = document.createElement('style');
    style.textContent = ':root{--app-viewport-height:100vh!important;--app-viewport-top:0px!important}' +
      'html,body{height:100%!important;margin:0!important}' +
      '#root{height:100vh!important;min-height:100vh!important}' +
      '#root>div{height:100vh!important;min-height:100vh!important}' +
      '[class*=frame-]{height:100vh!important}';
    (document.head || document.documentElement).appendChild(style);
  } catch (e) {}
})();
