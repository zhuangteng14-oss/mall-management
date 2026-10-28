顶栏搜索框可见性实测（2026-10-24，买家 1111 登录后 /#/customer，CDP headless Edge）

根因：AppShell.vue 原稿在 @media (max-width:1280px) 里写了 `.top-search { display:none; }`，
把 861–1280 这一整段（含 1280 笔记本）都收起了。修复：该断点改为只收内距、保留搜索框
（`min-width:180px`）；≤860 仍走第二行整行布局。

实测（measure.json 为原始产物，宽高为 getBoundingClientRect 取整）：
- 1440：可见，280×34，top=16
- 1280：可见，280×34，top=16
- 1024：可见，280×34，top=16
- 900 ：可见，280×34，top=16
- 861 ：可见，280×34，top=16
- 860 ：可见，809×34，top=65（换行到第二行，既有布局）

截图：searchbox-1440/1280/1024/900/861/860.png
