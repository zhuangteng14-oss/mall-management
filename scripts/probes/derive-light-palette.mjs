// derive-light-palette.mjs —— 浅色主题图表色板的**推导脚本**（可视化段 commit 1，2026-10-11）。
//
// 一次性推导：把丁版图表 10 色（为暗底 #101828 调的）压到白底上仍 ≥3:1 的版本。
// 手法 = OKLCH 里**只降 L、保 H**（彩度按 sRGB 色域夹逼），所以「色相身份」不变。
// 这不是新设计，是同一批色相换主题 —— 与 tokens.css 里 `--bg`/`--ink`/`--success` 等
// 「同一批语义名、按主题换值」的既有做法同款。
//
// 用法：`node scripts/probes/derive-light-palette.mjs` —— 打一张 10 行对照表
// （原色 / 白底对比 / 新色 / 白底对比），末行直接给出 `--c1`-`--c10` 的赋值语句，
// 粘进 `src/assets/tokens.css` 浅色 `:root` 即可。**不读写任何文件**，纯算。
// 判据：新色白底对比 ≥3.2（留 0.2 余量，因为 3.0 是「图形对象」的线、量测有舍入）；
// 原色本来就 ≥3.2 的**原值照搬**（`darken` 的搜索区间 `[0, 原 L]` 只降不升）。

const srgbToLinear = (c) => (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
const linearToSrgb = (c) => (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055);

const hexToRgb = (hex) => {
  const h = hex.replace('#', '');
  return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
};
const rgbToHex = (rgb) =>
  '#' + rgb.map((c) => Math.round(Math.min(255, Math.max(0, c))).toString(16).padStart(2, '0')).join('');

function rgbToOklab([r, g, b]) {
  const R = srgbToLinear(r / 255), G = srgbToLinear(g / 255), B = srgbToLinear(b / 255);
  const l = Math.cbrt(0.4122214708 * R + 0.5363325363 * G + 0.0514459929 * B);
  const m = Math.cbrt(0.2119034982 * R + 0.6806995451 * G + 0.1073969566 * B);
  const s = Math.cbrt(0.0883024619 * R + 0.2817188376 * G + 0.6299787005 * B);
  return [
    0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
  ];
}

function oklabToRgb([L, a, b]) {
  const l_ = L + 0.3963377774 * a + 0.2158037573 * b;
  const m_ = L - 0.1055613458 * a - 0.0638541728 * b;
  const s_ = L - 0.0894841775 * a - 1.2914855480 * b;
  const l = l_ ** 3, m = m_ ** 3, s = s_ ** 3;
  const R = +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
  const G = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
  const B = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;
  return [linearToSrgb(R) * 255, linearToSrgb(G) * 255, linearToSrgb(B) * 255];
}

const relLum = ([r, g, b]) =>
  0.2126 * srgbToLinear(r / 255) + 0.7152 * srgbToLinear(g / 255) + 0.0722 * srgbToLinear(b / 255);
const contrastVsWhite = (rgb) => 1.05 / (relLum(rgb) + 0.05);

const inGamut = (rgb) => rgb.every((c) => c >= -0.5 && c <= 255.5);

function oklch(hex) {
  const [L, a, b] = rgbToOklab(hexToRgb(hex));
  return { L, C: Math.hypot(a, b), H: Math.atan2(b, a) };
}
function fromOklch({ L, C, H }) {
  return oklabToRgb([L, C * Math.cos(H), C * Math.sin(H)]);
}

// 保 H、尽量保 C，降 L 至白底对比 ≥ target
function darken(hex, target) {
  const { L, C, H } = oklch(hex);
  let lo = 0, hi = L, best = null;
  for (let i = 0; i < 60; i++) {
    const mid = (lo + hi) / 2;
    // 色域夹逼：该 L 下能容纳的最大 C
    let cLo = 0, cHi = C;
    for (let j = 0; j < 40; j++) {
      const cMid = (cLo + cHi) / 2;
      if (inGamut(fromOklch({ L: mid, C: cMid, H }))) cLo = cMid; else cHi = cMid;
    }
    const rgb = fromOklch({ L: mid, C: cLo, H });
    if (contrastVsWhite(rgb) >= target) { best = rgb; lo = mid; } else { hi = mid; }
  }
  return best;
}

const SRC = ['#2f6fed', '#ff6a00', '#22c55e', '#f59e0b', '#a855f7',
             '#06b6d4', '#ef4444', '#84cc16', '#ec4899', '#38bdf8'];

console.log('原色           白底比    新色      白底比');
const out = [];
SRC.forEach((hex, i) => {
  const o = contrastVsWhite(hexToRgb(hex));
  const n = darken(hex, 3.2);      // 3.2 留一点余量（3.0 是线，量测有舍入）
  const nHex = rgbToHex(n);
  const nC = contrastVsWhite(n);
  out.push(nHex);
  console.log(
    `${hex}  ${o.toFixed(2).padStart(5)}    ${nHex}  ${nC.toFixed(2).padStart(5)}` +
    `${o >= 3 ? '   (原色已过线)' : ''}`
  );
});
console.log('\n--c1..--c10 =', out.map((h, i) => `--c${i + 1}: ${h};`).join(' '));
