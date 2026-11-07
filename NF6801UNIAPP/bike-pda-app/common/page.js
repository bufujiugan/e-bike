import ActionButton from "../components/ActionButton.vue";
import FormField from "../components/FormField.vue";
import ChoiceList from "../components/ChoiceList.vue";
import { refreshContext, canEnter } from "./auth.js";
import { session } from "./storage.js";
import { createDevice } from "./device.js";
import { pendingOperation, clearPending } from "./operations.js";
export const confirm = (title, content) =>
  new Promise((resolve) =>
    uni.showModal({
      title,
      content,
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false),
    }),
  );
export default {
  components: { ActionButton, FormField, ChoiceList },
  data: () => ({
    busy: false,
    ready: false,
    message: "",
    failed: false,
    context: null,
    pending: null,
    scanStarting: false,
  }),
  async onShow() {
    const generation = (this._showGeneration || 0) + 1;
    this._showGeneration = generation;
    this.ready = false;
    try {
      if (this.publicPage) {
        this.ready = true;
        return;
      }
      if (!session()) {
        uni.reLaunch({ url: "/pages/login/login" });
        return;
      }
      this.context = await refreshContext();
      if (generation !== this._showGeneration) return;
      if (this.context.mustChangePassword && this.mode !== "password") {
        uni.reLaunch({ url: "/pages/password/password" });
        return;
      }
      if (
        this.mode &&
        !["password", "home", "settings"].includes(this.mode) &&
        !canEnter(this.context, this.mode)
      )
        throw new Error("当前账号没有此工位权限");
      await this.run(async () => {
        if (this.load) await this.load();
      }, true);
      if (generation !== this._showGeneration) return;
      this.ready = !this.failed;
      if (this.onScan && this.ready) {
        this._device = createDevice();
        try {
          this._device.open((code) => {
            if (!this.ready || this.busy) {
              uni.showToast({
                title: "上一条仍在处理，请稍后重扫",
                icon: "none",
              });
              return;
            }
            this.run(() => this.onScan(code));
          });
        } catch (cause) {
          this.message = cause.message + "；可使用手工输入";
          this.failed = true;
        }
      }
    } catch (cause) {
      this.failed = true;
      this.message = cause.message;
    }
  },
  onHide() {
    this._showGeneration = (this._showGeneration || 0) + 1;
    this.ready = false;
    if (this._device) this._device.close();
  },
  onUnload() {
    this._showGeneration = (this._showGeneration || 0) + 1;
    if (this._device) this._device.close();
  },
  onBackPress() {
    if (this.busy) {
      uni.showToast({ title: "操作处理中，请等待结果", icon: "none" });
      return true;
    }
    return false;
  },
  methods: {
    async run(action, allowUnready = false) {
      if (this.busy) return;
      if (!this.ready && !allowUnready) {
        this.message = "页面尚未就绪，请返回后重试";
        this.failed = true;
        return;
      }
      this.busy = true;
      this.message = "";
      this.failed = false;
      try {
        await action();
      } catch (cause) {
        this.failed = true;
        this.message = cause.message || "操作失败";
      } finally {
        this.busy = false;
        if (this.operationName && session())
          this.pending = pendingOperation(this.operationName);
      }
    },
    async triggerScan() {
      if (this.busy || !this.ready || this.scanStarting) return;
      this.scanStarting = true;
      const generation = this._showGeneration;
      try {
        if (!this._device) throw new Error("设备未就绪");
        this.failed = false;
        this.message = "正在请求扫描头，请对准条码";
        await this._device.scan();
      } catch (cause) {
        if (generation === this._showGeneration) {
          this.failed = true;
          this.message = cause.message;
        }
      } finally {
        this.scanStarting = false;
      }
    },
    go(path) {
      if (!this.busy) uni.navigateTo({ url: "/pages/" + path });
    },
    async acknowledgePending() {
      if (
        await confirm(
          "已核对服务器结果",
          "请通过任务恢复或追溯确认实际结果。解除锁定不会撤销服务器记录，未确认前不要重复提交。",
        )
      ) {
        clearPending(this.operationName);
        this.pending = null;
      }
    },
  },
};
