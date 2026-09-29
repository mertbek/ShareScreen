package com.mertbek.sharescreen.web

@JsFun("() => window.location.href")
internal external fun pageUrl(): String

@JsFun("() => { history.replaceState(null, '', window.location.pathname); }")
internal external fun clearPageFragment()

@JsFun("() => navigator.userAgent")
internal external fun userAgent(): String

@JsFun("(url) => { window.open(url, '_blank', 'noopener'); }")
internal external fun openWindow(url: String)

@JsFun("(text) => { navigator.clipboard.writeText(text).catch(() => {}); }")
internal external fun writeClipboard(text: String)

@JsFun(
    "(text) => { if (navigator.share) { navigator.share({ text: text }).catch(() => {}); } else { navigator.clipboard.writeText(text).catch(() => {}); } }"
)
internal external fun shareText(text: String)

@JsFun(
    "() => { const splash = document.getElementById('splash'); if (splash) { splash.classList.add('done'); setTimeout(() => splash.remove(), 400); } }"
)
internal external fun hideSplash()
