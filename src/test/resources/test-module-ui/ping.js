export const qodConsoleContract = 1;
export function mount(el, ctx) {
  const p = document.createElement('p');
  p.className = 'test-module-ping';
  p.textContent = 'ping page for ' + ctx.user.username + ' at ' + (ctx.subpath || '/');
  el.appendChild(p);
  const off = ctx.onLocationChange((s) => { p.textContent = 'ping page for ' + ctx.user.username + ' at ' + (s || '/'); });
  return () => { off(); el.removeChild(p); };
}
