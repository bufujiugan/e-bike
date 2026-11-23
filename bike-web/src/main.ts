import { computed, createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus, { provideGlobalConfig } from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import { componentLocale } from './i18n/language'
import './styles/tokens.css'
import './styles/smart-manufacturing.css'
import './styles/command-center.css'
import './styles/manufacturing-shell.css'
import './styles/production-center.css'
import './styles/pastel-theme.css'
import './styles/sales-routing.css'

const app = createApp(App).use(createPinia()).use(ElementPlus)
provideGlobalConfig(computed(() => ({ locale: componentLocale.value })), app, true)
app.mount('#app')
