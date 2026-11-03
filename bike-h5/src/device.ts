import { onMounted, onUnmounted } from 'vue'
import { showFailToast } from 'vant'

type NativeDevice = {
  scan(): void
  settings(): void
  info(): string
  printBitmap(id: string, png: string): void
}
declare global {
  interface Window { BikePda?: NativeDevice }
}
export const isPda = Boolean(window.BikePda)

/** 每个页面显式接收扫描事件，不向密码、搜索框或隐藏页面模拟键盘输入。 */
export function useDeviceScan(handler: (code: string) => void, active: () => boolean = () => true) {
  const receive = (event: Event) => {
    const code = (event as CustomEvent).detail?.code
    if (typeof code !== 'string' || !code.trim() || code.length > 4096) return
    if (!active() || Array.from(document.querySelectorAll<HTMLElement>('.van-dialog, .van-popup--center:not(.van-toast), .van-popup--bottom')).some(element => element.getClientRects().length > 0 && getComputedStyle(element).display !== 'none')) return
    handler(code.trim())
  }
  onMounted(() => window.addEventListener('bike-device-scan', receive))
  onUnmounted(() => window.removeEventListener('bike-device-scan', receive))
}

export function startDeviceScan() { window.BikePda?.scan() }
export function openDeviceSettings() { window.BikePda?.settings() }
export function scanBusy() { showFailToast('正在处理上一笔，请稍后重新扫描') }

export async function printDeviceLabel(frame: HTMLIFrameElement, jobId: string) {
  if (!window.BikePda || !frame.contentDocument) throw new Error('手持机打印连接不可用')
  const { default: html2canvas } = await import('html2canvas')
  const label = frame.contentDocument.querySelector<HTMLElement>('.label')
  if (!label) throw new Error('标签内容无效，请刷新标签')
  const info = JSON.parse(window.BikePda.info()) as { printWidth: number }
  const width = info.printWidth === 570 ? 570 : 384
  const canvas = await html2canvas(label, { scale: width / label.getBoundingClientRect().width, backgroundColor: '#fff', logging: false })
  if (canvas.height > 2400) throw new Error('标签过长，请使用适合手持机的标签模板')
  const png = canvas.toDataURL('image/png').split(',')[1]!
  return new Promise<string>((resolve, reject) => {
    const finish = (event: Event) => {
      const result = (event as CustomEvent).detail
      if (result?.id !== jobId) return
      cleanup()
      if (result.status === 'SUBMITTED') resolve(result.message)
      else reject(new Error(result.message || '打印结果未知，请核对实物后回报，勿重复送印'))
    }
    const cleanup = () => { clearTimeout(timer); window.removeEventListener('bike-device-print', finish) }
    const timer = setTimeout(() => { cleanup(); reject(new Error('打印等待超时，请核对实物并回报结果，勿重复送印')) }, 65000)
    window.addEventListener('bike-device-print', finish)
    try { window.BikePda!.printBitmap(jobId, png) } catch (cause) { cleanup(); reject(cause) }
  })
}
