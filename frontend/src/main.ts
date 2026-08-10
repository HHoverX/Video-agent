import { createPinia } from 'pinia'
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import { installApiInterceptors } from './services/api'
import './styles/main.css'

const pinia = createPinia()
installApiInterceptors(pinia, router)

createApp(App)
  .use(pinia)
  .use(router)
  .use(ElementPlus)
  .mount('#app')
