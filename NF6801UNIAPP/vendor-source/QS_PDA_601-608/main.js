import App from './App'

// #ifndef VUE3
import Vue from 'vue'
import VueI18n from 'vue-i18n';
import Messages from './pages/api/language.js';
Vue.use(VueI18n);
const i18n = new VueI18n({  
  locale: 'zh-CN',  //zh-CN en-US
  messages:Messages  //language中的语言包
})  

Vue.prototype._i18n = i18n

Vue.config.productionTip = false
App.mpType = 'app'

const app = new Vue({
		i18n,
    ...App
})
app.$mount()
// #endif

// #ifdef VUE3
import { createSSRApp } from 'vue'
export function createApp() {
  const app = createSSRApp(App)
  return {
    app
  }
}
// #endif