package com.omarchytv.home;

/**
 * v2.14 the X app on the TV: x.com in OmarchyTV's own web view (nothing else bundled, so it stays light), with the
 * remote mapped to X's keyboard shortcuts, injected into the page: ▲▼ previous/next post (k/j), OK opens it,
 * 1 like (l), 2 reply (r), 3 repost (t), 4 new post (n), 5 search (/), 0 home (g h), 9 notifications (g n),
 * 8 messages (g m). Never while you type in a text field. Autoplaying video waits for OK.
 */
final class XKeys {
    private XKeys() {
    }

    static boolean isX(String id, String url) {
        if ("x".equals(id)) return true;
        if (url == null) return false;
        String u = url.toLowerCase(java.util.Locale.ROOT);
        return u.startsWith("https://x.com/") && !u.startsWith("https://x.com/i/grok");
    }

    /** Runs in the page after each load; installs one capturing keydown handler per document. */
    static final String JS = "(function(){if(window.__otvX)return;window.__otvX=1;"
            + "var M={ArrowDown:['j'],ArrowUp:['k'],'1':['l'],'2':['r'],'3':['t'],'4':['n'],'5':['/'],'0':['g','h'],'9':['g','n'],'8':['g','m']};"
            + "function ed(){var a=document.activeElement;return !!a&&(a.isContentEditable||/^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName));}"
            + "function send(k){var t=document.activeElement&&document.activeElement!==document.body?document.activeElement:document;"
            + "['keydown','keypress','keyup'].forEach(function(ty){var e=new KeyboardEvent(ty,{key:k,code:k==='/'?'Slash':'Key'+k.toUpperCase(),bubbles:true,cancelable:true});e.__otv=1;t.dispatchEvent(e);});}"
            + "window.addEventListener('keydown',function(e){if(e.__otv||ed()||e.ctrlKey||e.altKey||e.metaKey)return;var s=M[e.key];if(!s)return;"
            + "e.preventDefault();e.stopImmediatePropagation();s.forEach(function(k,i){setTimeout(function(){send(k);},i*60);});},true);})();";
}
