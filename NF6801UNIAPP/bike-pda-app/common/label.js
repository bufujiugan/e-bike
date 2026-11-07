// 使用 SDK 已知的文本、二维码和线段原语；不请求或渲染服务器 HTML 标签。
import { maxPrintableDots } from "../config.js";

const LEFT = [
  "0001101",
  "0011001",
  "0010011",
  "0111101",
  "0100011",
  "0110001",
  "0101111",
  "0111011",
  "0110111",
  "0001011",
];
const EVEN = [
  "0100111",
  "0110011",
  "0011011",
  "0100001",
  "0011101",
  "0111001",
  "0000101",
  "0010001",
  "0001001",
  "0010111",
];
const PARITY = [
  "LLLLLL",
  "LLGLGG",
  "LLGGLG",
  "LLGGGL",
  "LGLLGG",
  "LGGLLG",
  "LGGGLL",
  "LGLGLG",
  "LGLGGL",
  "LGGLGL",
];
export function barcodeBits(value, kind) {
  let digits = String(value);
  if (kind === "UPC_A") {
    if (!/^\d{12}$/.test(digits)) throw new Error("UPC 必须为 12 位");
    digits = "0" + digits;
  }
  if (!/^\d{13}$/.test(digits)) throw new Error("EAN 必须为 13 位");
  const sum = digits
    .slice(0, 12)
    .split("")
    .reduce(
      (total, digit, index) => total + Number(digit) * (index % 2 ? 3 : 1),
      0,
    );
  if ((10 - (sum % 10)) % 10 !== Number(digits[12]))
    throw new Error("商品条码校验位不正确");
  let bits = "101";
  for (let index = 1; index <= 6; index++)
    bits += (PARITY[Number(digits[0])][index - 1] === "L" ? LEFT : EVEN)[
      Number(digits[index])
    ];
  bits += "01010";
  for (let index = 7; index < 13; index++)
    bits += LEFT[Number(digits[index])].replace(/[01]/g, (bit) =>
      bit === "0" ? "1" : "0",
    );
  return bits + "101";
}
export function snapshotOf(job) {
  let snapshot = job.snapshot;
  if (typeof snapshot === "string") {
    try {
      snapshot = JSON.parse(snapshot);
    } catch (cause) {
      throw new Error("打印快照格式无效");
    }
  }
  if (!snapshot || !snapshot.qrCode || !snapshot.frameSn)
    throw new Error("打印快照缺少车辆标识");
  return snapshot;
}
export const labelFields = [
  ["heading", "标题"],
  ["modelName", "车型"],
  ["productName", "商品"],
  ["skuCode", "SKU"],
  ["specification", "规格"],
  ["ean", "EAN"],
  ["upc", "UPC"],
  ["batchNo", "批次"],
  ["frameSn", "车架"],
  ["motorSn", "电机"],
  ["batterySn", "电池"],
  ["displaySn", "仪表"],
  ["qrCode", "聚合码"],
  ["templateName", "模板"],
  ["templateVersion", "模板版本"],
  ["layout", "版式"],
  ["widthMm", "宽度 mm"],
  ["heightMm", "高度 mm"],
];
export function buildPaper(job, config) {
  if (config.labelProfile === "608_80X100") return build608Paper(job, config);
  const s = snapshotOf(job),
    dpi = Number(config.dpi),
    dots = Number(config.printableDots),
    paperMm = Number(config.paperWidthMm);
  if (
    !Number.isFinite(dpi) ||
    dpi < 150 ||
    dpi > 600 ||
    !Number.isFinite(dots) ||
    dots < 200 ||
    !Number.isFinite(paperMm) ||
    paperMm <= 0
  )
    throw new Error("请先在设备设置中填写实测纸宽、可打印点数和 DPI");
  const width = Math.round((Number(s.widthMm) * dpi) / 25.4),
    height = Math.round((Number(s.heightMm) * dpi) / 25.4);
  if (
    !Number.isFinite(width) ||
    !Number.isFinite(height) ||
    width <= 0 ||
    height <= 0 ||
    height > 8000
  )
    throw new Error("标签尺寸无效");
  const documentedMaxDots = maxPrintableDots(paperMm),
    effectiveDots = documentedMaxDots
      ? Math.min(dots, documentedMaxDots)
      : dots;
  if (Number(s.widthMm) > paperMm || width > effectiveDots)
    throw new Error(
      `模板宽 ${s.widthMm}mm / ${width} 点，超过设备纸宽或打印头上限 ${effectiveDots} 点。80mm 纸的厂商上限为 570 点，需配置不超过实际打印宽度的标签模板，禁止压缩外箱标签`,
    );
  const elements = [],
    margin = Math.round((dpi * 3) / 25.4),
    size = Math.round((dpi * 2.5) / 25.4),
    usable = width - 2 * margin;
  let y = margin;
  const addText = (text, font = size) => {
    let line = "",
      units = 0;
    const flush = () => {
      elements.push({
        type: 1,
        data: line,
        width: usable,
        x: margin,
        y,
        size: font,
      });
      y += font + 6;
      line = "";
      units = 0;
    };
    for (const char of String(text)) {
      const weight = char.charCodeAt(0) > 255 ? font : Math.ceil(font * 0.62);
      if (char === "\n") {
        flush();
        continue;
      }
      if (units + weight > usable) flush();
      line += char;
      units += weight;
    }
    if (line) flush();
  };
  addText(s.heading || "整车标签", size + 6);
  for (const [key, title] of labelFields.slice(1, 8)) {
    if (s[key]) addText(`${title}: ${s[key]}`);
  }
  for (const [key, kind] of [
    ["ean", "EAN_13"],
    ["upc", "UPC_A"],
  ]) {
    if (!s[key]) continue;
    const bits = barcodeBits(s[key], kind),
      unit = Math.floor(usable / 117);
    if (unit < 2) throw new Error("可打印宽度不足以保留商品条码和静区");
    const x = margin + 11 * unit,
      barHeight = Math.round((dpi * 12) / 25.4);
    for (let index = 0; index < bits.length; index++)
      if (bits[index] === "1")
        elements.push({
          type: 4,
          x: x + index * unit + unit / 2,
          y,
          stopX: x + index * unit + unit / 2,
          stopY: y + barHeight,
          strokeWidth: unit,
          isDotted: 0,
        });
    y += barHeight + 8;
    addText(kind + ": " + s[key]);
  }
  const side = Math.round(((s.layout === "COMPACT" ? 30 : 38) * dpi) / 25.4);
  if (side > usable) throw new Error("模板二维码尺寸超过可打印宽度");
  elements.push({
    type: 3,
    data: s.qrCode,
    side,
    x: margin,
    y,
    displayCode: 0,
    fontSize: size,
  });
  y += side + 12;
  for (const [key, title] of labelFields.slice(8, 12))
    addText(`${title}: ${s[key] || "—"}`);
  addText(`${s.templateName || ""} · V${s.templateVersion} · ${job.id}`);
  if (y + margin > height)
    throw new Error(
      `完整标签内容需要至少 ${Math.ceil(((y + margin) * 25.4) / dpi)}mm 高，当前模板 ${s.heightMm}mm；请调整模板，不能截断内容`,
    );
  return { attrs: { width, height }, elements };
}

/** 80×100 标签重新排版，原始任务快照不变；点阵宽度不是纸张物理宽度。 */
export function build608Paper(job, config) {
  const s = snapshotOf(job);
  if (
    Number(config.paperWidthMm) !== 80 ||
    Number(config.paperHeightMm) !== 100 ||
    Number(config.dpi) !== 203 ||
    Number(config.printableDots) !== 570 ||
    !config.blackMark
  )
    throw new Error(
      "608 标签配置必须为 80×100mm、570 点、203 DPI，并开启间隙标签模式",
    );
  const width = 570,
    font = 20,
    margin = 10,
    gap = 12;
  const logicalHeight = Math.round((100 * 203) / 25.4);
  // 底部保留 7mm；同时为设置中的起始偏移预留点阵空间，避免越过间隙。
  const offset = Number(config.offset);
  if (!Number.isInteger(offset) || offset < 0 || offset > 255)
    throw new Error("黑标偏移无效");
  const maxHeight = logicalHeight - Math.ceil((7 * 203) / 25.4) - offset;
  const elements = [];
  const text = (value, x, top, availableWidth, size = font) => {
    let line = "",
      units = 0,
      y = top;
    const flush = () => {
      elements.push({ type: 1, data: line, x, y, width: availableWidth, size });
      y += Math.ceil(size * 1.25);
      line = "";
      units = 0;
    };
    for (const character of String(value)) {
      const weight =
        character.codePointAt(0) > 255 ? size : Math.ceil(size * 0.62);
      if (character === "\n") {
        flush();
        continue;
      }
      if (units + weight > availableWidth) flush();
      line += character;
      units += weight;
    }
    if (line) flush();
    return y;
  };
  let y = text(s.heading || "整车标签", margin, margin, width - margin * 2, 24);
  for (const [key, title] of labelFields.slice(1, 8)) {
    if (["ean", "upc"].includes(key) || !s[key]) continue;
    y = text(`${title}: ${s[key]}`, margin, y, width - margin * 2);
  }
  for (const [key, kind] of [
    ["ean", "EAN_13"],
    ["upc", "UPC_A"],
  ]) {
    if (!s[key]) continue;
    const bits = barcodeBits(s[key], kind),
      unit = 3,
      barHeight = 72;
    const left = Math.floor((width - 117 * unit) / 2) + 11 * unit;
    y += 5;
    for (let index = 0; index < bits.length; index++)
      if (bits[index] === "1") {
        elements.push({
          type: 4,
          x: left + index * unit + unit / 2,
          y,
          stopX: left + index * unit + unit / 2,
          stopY: y + barHeight,
          strokeWidth: unit,
          isDotted: 0,
        });
      }
    y = text(
      `${key.toUpperCase()}: ${s[key]}`,
      margin,
      y + barHeight + 3,
      width - margin * 2,
    );
  }
  y += gap;
  const side = 240,
    rightX = margin + side + gap;
  elements.push({
    type: 3,
    data: s.qrCode,
    side,
    x: margin,
    y,
    displayCode: 0,
    fontSize: font,
  });
  let serialY = y;
  for (const [key, title] of labelFields.slice(8, 12))
    serialY = text(
      `${title}: ${s[key] || "—"}`,
      rightX,
      serialY,
      width - rightX - margin,
    );
  y = Math.max(y + side, serialY) + 6;
  y = text(
    `${s.templateName || ""} V${s.templateVersion} / ${job.id}`,
    margin,
    y,
    width - margin * 2,
    18,
  );
  if (y + 4 > maxHeight)
    throw new Error(
      "完整标签内容超出 80×100mm 的安全打印区域；未截断或缩小条码，请检查字段长度和黑标起始偏移",
    );
  // 不发送整张纸高的空白位图；由厂商标签走纸指令定位下一间隙。
  return { attrs: { width, height: y + 4 }, elements };
}

export function buildHardwareTestPaper(config) {
  return buildPaper(
    {
      id: "SELF-TEST",
      snapshot: {
        heading: "BIKE 设备自检（非业务标签）",
        modelName: "608 / 80×100mm",
        productName: "打印后请扫描二维码",
        frameSn: "TEST-FRAME",
        motorSn: "TEST-MOTOR",
        batterySn: "TEST-BATTERY",
        displaySn: "TEST-DISPLAY",
        ean: "4006381333931",
        upc: "036000291452",
        qrCode: "BIKE-PDA-SELF-TEST",
        templateName: "自检",
        templateVersion: 1,
        widthMm: 80,
        heightMm: 100,
        layout: "COMPACT",
      },
    },
    config,
  );
}
