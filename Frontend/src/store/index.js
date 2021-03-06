import Vue from 'vue'
import Vuex from 'vuex'

Vue.use(Vuex)

// store文件用于存储用户登录后的账号信息，进入课程界面后的课程名（避免反复调用后端接口）以及用户身份

export default new Vuex.Store({
  state: {
    course: window.localStorage.getItem('course') == null ?　'': JSON.parse(window.localStorage.getItem('course'||'[]')),
    user: window.localStorage.getItem('user') ==null?'':JSON.parse(window.localStorage.getItem('user'||'[]'))
    // adminMenus: []
  },
  mutations: {
    // initAdminMenu (state, menus) {
    //   state.adminMenus = menus
    // },
    login (state, data) {
      state.user = data
      window.localStorage.setItem('user', JSON.stringify(data))
    },
    enterCourse(state, data){
      state.course = data
      window.localStorage.setItem('course', JSON.stringify(data))
    },
    logout (state) {
      // 注意不能用 null 清除，否则将无法判断 user 里具体的内容
      state.course = []
      window.localStorage.removeItem('course')
      state.user = []
      window.localStorage.removeItem('user')
    }
  },
  actions: {
  }
})
