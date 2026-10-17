// One controller per card. Pointer movement cancels a hold so scrolling never
// opens an action menu. The next click after a hold is swallowed once.
export function createLongPressController(callback, delay = 580) {
  let timer, origin, held = false;
  const cancel = () => { clearTimeout(timer); timer = undefined; };
  return {
    cancel,
    updateCallback: next => { callback = next; },
    handlers: {
      onPointerDown(e) { if (e.button !== 0) return; cancel(); origin = { x: e.clientX, y: e.clientY }; held = false; timer = setTimeout(() => { held = true; callback(); }, delay); },
      onPointerUp: cancel, onPointerCancel: cancel, onPointerLeave: cancel,
      onPointerMove(e) { if (origin && Math.hypot(e.clientX - origin.x, e.clientY - origin.y) > 10) cancel(); },
      onContextMenu(e) { e.preventDefault(); cancel(); held = true; callback(); },
      onClickCapture(e) { if (held) { e.preventDefault(); e.stopPropagation(); held = false; } },
    },
  };
}
