<template>
  <article class="role-account-guide">
    <header class="guide-lead">
      <h2>角色与账户</h2>
      <p>账号类型决定进入哪个工作台和所属范围；角色授予可访问的页面与操作权限。权限由服务端校验，菜单是否显示不替代权限控制。</p>
    </header>

    <section class="guide-section">
      <h3>账号类型</h3>
      <div class="guide-table-wrap">
        <table>
          <thead><tr><th>账号类型</th><th>必须绑定</th><th>登录后的范围</th></tr></thead>
          <tbody>
            <tr><td>WEB_ADMIN</td><td>管理员角色</td><td>内部 Web；SUPER_ADMIN 拥有系统管理权限</td></tr>
            <tr><td>WEB_OPERATOR</td><td>至少一个业务角色</td><td>内部 Web；可见功能由角色权限决定</td></tr>
            <tr><td>PDA_OPERATOR</td><td>操作工位及岗位角色</td><td>PDA/H5；按工位开放扫码作业</td></tr>
            <tr><td>AGENT</td><td>代理商公司</td><td>代理商工作台；仅限授权代理商及车型范围</td></tr>
            <tr><td>CUSTOMER</td><td>客户公司</td><td>客户工作台；仅查看和提交所属公司的订单</td></tr>
          </tbody>
        </table>
      </div>
      <p class="guide-note">普通员工按岗位分配最少需要的角色；不要把 SUPER_ADMIN 用作日常账号。</p>
    </section>

    <section class="guide-section">
      <h3>新增内部账号并分配角色</h3>
      <ol>
        <li>使用超级管理员账号登录，打开 <strong>资料与设置 → 用户管理</strong>，点击“新增用户”。</li>
        <li>填写用户名、初始密码和姓名，选择账号类型。PDA 操作员必须选择工位；代理商账号必须绑定代理商公司。</li>
        <li>在“角色”中选择该岗位所需角色并保存。账号需要多个岗位权限时可选择多个角色。</li>
        <li>新建或调整角色时打开 <strong>资料与设置 → 角色权限</strong>，创建/编辑角色并勾选功能权限。回到“用户管理”，编辑目标账号即可调整它的角色。</li>
      </ol>
    </section>

    <section class="guide-section">
      <h3>新增客户登录账号</h3>
      <ol>
        <li>在 <strong>资料与设置 → 用户管理</strong> 点击“新增客户登录账号”。</li>
        <li>选择新建客户公司或绑定已有客户公司，再填写登录账号、联系人姓名和初始密码。</li>
        <li>保存后系统自动设置 CUSTOMER 账号类型、绑定所属客户公司并分配 WEB_CUSTOMER 角色；客户账号不需要在角色页手动配置。</li>
        <li>客户使用与员工相同的登录入口。首次登录必须修改密码，随后自动进入客户工作台；没有公开自助注册入口。</li>
      </ol>
    </section>

    <section class="guide-section guide-section-last">
      <h3>日常维护</h3>
      <ul>
        <li>新增员工岗位或调整可见功能：先在“角色权限”维护角色权限，再在“用户管理”把角色分配给账号。</li>
        <li>代理商车型范围在代理商资料的“车型授权”中维护；PDA 账号的工位在用户资料中维护。</li>
        <li>账号离岗或不再使用时，在“用户管理”停用账号；停用后该账号不能继续登录。</li>
      </ul>
    </section>
  </article>
</template>

<style scoped>
.role-account-guide{color:#344b61;padding:2px 4px 12px;line-height:1.75}.guide-lead{padding:4px 0 14px;border-bottom:1px solid #e3eaf0}.guide-lead h2{margin:0 0 5px;color:#183b5b;font-size:18px}.guide-lead p,.guide-section p{margin:0;color:#60758a;font-size:13px}.guide-section{padding:17px 0;border-bottom:1px solid #e7edf2}.guide-section h3{margin:0 0 10px;color:#183b5b;font-size:15px}.guide-table-wrap{max-width:100%;overflow-x:auto}.guide-account-table{border-collapse:collapse}.guide-section table{width:100%;border-collapse:collapse;font-size:13px}.guide-section th,.guide-section td{padding:9px 10px;border:1px solid #e1e8ee;text-align:left;vertical-align:top}.guide-section th{background:#f2f6fa;color:#425b72;font-weight:600}.guide-section td:first-child{font-weight:600;color:#244b6b;white-space:nowrap}.guide-section ol,.guide-section ul{margin:0;padding-left:23px;font-size:13px}.guide-section li{padding:3px 0 3px 2px}.guide-note{margin-top:9px!important;padding:8px 11px;background:#f5f8fb;border-left:3px solid #74a3cc}.guide-section-last{border-bottom:0}@media(max-width:600px){.guide-section table{min-width:660px}.guide-lead h2{font-size:16px}.guide-section h3{font-size:14px}}
</style>
