import { createApp } from 'vue'
import App from './App.vue'
import router from './router/index.js'

const app = createApp(App)
app.config.devtools = false   
app.use(router)

router.isReady().then(() => {
  app.mount('#app')
})