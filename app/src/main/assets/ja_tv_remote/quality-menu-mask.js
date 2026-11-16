/* Bounded menu-only fallback for provider styles or dynamically replaced menus. */
var JaQualityMenuMask = (() => {
  'use strict';
  const attribute = 'data-ja-quality-transaction';
  const originalClip = 'data-ja-quality-original-clip';
  function create({doc, observe = callback => new MutationObserver(callback), report = () => {}}) {
    let active = false, observer = null;
    const saved = new Map();
    function scan() {
      if (!active) return;
      let added = 0;
      for (const menu of doc.querySelectorAll('.jw-menu')) {
        if (saved.has(menu)) continue;
        let prior = {
          value: menu.style.getPropertyValue('clip-path'),
          priority: menu.style.getPropertyPriority('clip-path')
        };
        // A provider can clone a masked menu. Carry its original clipping
        // through the clone so cleanup never leaves it permanently hidden.
        if (prior.value === 'inset(100%)' && prior.priority === 'important') {
          try {
            const inherited = JSON.parse(menu.getAttribute(originalClip));
            if (typeof inherited?.value === 'string' && typeof inherited?.priority === 'string') prior = inherited;
          } catch (_) {}
        }
        saved.set(menu, prior);
        menu.setAttribute(originalClip, JSON.stringify(prior));
        menu.style.setProperty('clip-path', 'inset(100%)', 'important');
        added++;
      }
      if (added) report('QUALITY_MENU_MASK_APPLIED', {menus: saved.size, method: 'EXTENSION_CSS_AND_INLINE_CLIP'});
    }
    function release() {
      if (!active) return;
      active = false;
      observer?.disconnect(); observer = null;
      // Restore clipping before removing the stylesheet's guard, in one task.
      for (const [menu, prior] of saved) {
        menu.removeAttribute(originalClip);
        if (menu.style.getPropertyValue('clip-path') !== 'inset(100%)' ||
            menu.style.getPropertyPriority('clip-path') !== 'important') continue;
        if (prior.value) menu.style.setProperty('clip-path', prior.value, prior.priority);
        else menu.style.removeProperty('clip-path');
      }
      saved.clear();
      doc.documentElement.removeAttribute(attribute);
      report('QUALITY_MENU_MASK_RELEASED', {});
    }
    function set(value) {
      if (!value) { release(); return; }
      if (active) return;
      active = true;
      doc.documentElement.setAttribute(attribute, 'true');
      try {
        scan();
        observer = observe(scan);
        observer.observe(doc.documentElement, {childList: true, subtree: true});
      } catch (error) { release(); throw error; }
    }
    return {set};
  }
  return {create};
})();
if (typeof module !== 'undefined') module.exports = JaQualityMenuMask;
