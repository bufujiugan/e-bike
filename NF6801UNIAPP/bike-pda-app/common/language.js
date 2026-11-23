import english from "./en.json";

const storageKey = "bike_language";
const languages = [
  { value: "en", label: "English" },
  { value: "zh-CN", label: "简体中文" },
];
const messages = new Map(Object.entries(english));
const pageTitles = {
  home: "作业工作台",
  password: "修改密码",
  assembly: "生产组装",
  "stock-in": "扫码入库",
  "stock-out": "订单出库",
  pi: "PI 整批出库",
  "stock-lookup": "库存查询",
  rework: "返工换件",
  trace: "全链路追溯",
  issues: "生产异常与图片",
  prints: "标签打印",
  settings: "服务器与设备设置",
};

export function getLanguage() {
  return uni.getStorageSync(storageKey) === "en" ? "en" : "zh-CN";
}

export function translate(source, language = getLanguage()) {
  return language === "en" ? messages.get(source) ?? source : source;
}

export function setPageTitle(mode, language) {
  uni.setNavigationBarTitle({
    title: translate(pageTitles[mode] ?? "BIKE PDA 登录", language),
  });
}

export function chooseLanguage(apply) {
  const current = getLanguage();
  uni.showActionSheet({
    itemList: languages.map(({ value, label }) => value === current ? label + "  ✓" : label),
    success({ tapIndex }) {
      const option = languages[tapIndex];
      if (!option || option.value === current) return;
      uni.setStorageSync(storageKey, option.value);
      apply(option.value);
    },
  });
}
