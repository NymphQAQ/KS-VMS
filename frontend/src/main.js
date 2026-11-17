import { createApp } from 'vue'
import './styles.css'

const visitor = location.pathname.startsWith('/visitor')
const { default: App } = visitor ? await import('./Visitor.vue') : await import('./Workspace.vue')
createApp(App).mount('#app')
