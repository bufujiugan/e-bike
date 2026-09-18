async (page) => {
  const snapshot = async () => await page.locator('body').ariaSnapshot();
  const capture = async (name, mobile=false, modal=false) => {
    await page.setViewportSize({width:mobile?390:1440,height:mobile?844:1080});
    await page.evaluate(()=>{document.activeElement?.blur?.();document.getElementById('toast').classList.remove('show');window.scrollTo(0,0)});
    if(!mobile&&!modal){
      const height=await page.evaluate(()=>Math.max(1080,document.documentElement.scrollHeight));
      await page.setViewportSize({width:1440,height});
    }
    await page.screenshot({path:'F:/bike/output/playwright/ebike-hifi/'+name+'.png',fullPage:false});
    await page.setViewportSize({width:1440,height:1080});
  };
  await page.goto('http://127.0.0.1:8766/#dashboard');await snapshot();
  await page.getByRole('combobox',{name:'切换原型角色'}).selectOption('admin');await snapshot();
  await capture('01-admin-dashboard');
  await page.getByRole('button',{name:'TW-AS-20260909-0008',exact:true}).click();await snapshot();
  await capture('03-case-detail');
  for(const [label,name] of [['维修方案','04-repair-plan'],['发运信息','05-shipping-detail'],['旧件处理','06-old-parts'],['变更记录','07-case-history']]){
    await page.getByRole('button',{name:label,exact:label!=='旧件处理'}).click();await snapshot();await capture(name);
  }
  for(const [hash,name] of [['cases','02-case-list'],['shipments','08-warehouse'],['dealers','09-customers'],['vehicles','10-vehicle'],['inventory','11-inventory'],['production','12-production']]){
    await page.goto('http://127.0.0.1:8766/#'+hash);await snapshot();await capture(name);
  }
  await page.goto('http://127.0.0.1:8766/#approvals');await snapshot();
  await page.getByRole('button',{name:'TW-AS-20260909-0011',exact:true}).click();await snapshot();
  await page.getByRole('button',{name:'审批案例',exact:true}).click();await snapshot();await capture('13-approval-dialog',false,true);
  await page.getByRole('button',{name:'关闭弹窗',exact:true}).click();
  await page.getByRole('combobox',{name:'切换原型角色'}).selectOption('customer');await snapshot();
  await capture('14-customer-dashboard');
  await capture('16-mobile-customer',true);
  await page.getByRole('button',{name:'申报售后案例',exact:true}).click();await snapshot();await capture('15-new-case');
  await page.getByRole('combobox',{name:'切换原型角色'}).selectOption('admin');await snapshot();
  return '16 张最终截图已输出，长页面采用完整视口渲染，初始演示数据已保留。';
}
