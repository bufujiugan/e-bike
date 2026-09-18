import fs from 'node:fs';
import path from 'node:path';

const root='F:/bike/output/prototype';
let html=fs.readFileSync(path.join(root,'index.html'),'utf8');
const css=fs.readFileSync(path.join(root,'prototype.css'),'utf8');
const js=fs.readFileSync(path.join(root,'prototype.js'),'utf8');
html=html.replace('<link rel="stylesheet" href="prototype.css">',()=>`<style>\n${css}\n</style>`);
html=html.replace('  <script defer src="prototype.js"></script>','');
html=html.replace('</body>',()=>`<script>\n${js.replace(/<\/script/gi,'<\\/script')}\n</script>\n</body>`);
const output=path.join(root,'E-Bike_高保真交互原型.html');
fs.writeFileSync(output,html,'utf8');
if(/src="prototype\.js"|href="prototype\.css"/.test(html))throw new Error('单文件版本仍有外部依赖');
console.log(`已生成单文件原型：${output} (${Buffer.byteLength(html)} bytes)`);
