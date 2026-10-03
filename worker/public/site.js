// Turkish and English text for the static pages; the browser's language decides.
const TEXT = {
  en: {
    lead: "Share an Android screen with sound and let a friend watch it, or even control it, on the same Wi-Fi or over the internet.",
    download: "Download for Android",
    install: "Shares and watches screens, and controls other devices. Android 10 or newer. Your phone will ask you to allow installing apps from this source.",
    downloadFull: "Download the full version",
    installFull: "Also lets other devices control this one. It uses an accessibility service, which Google Play Protect blocks on some phones.",
    f1: "Picture and sound go straight between the devices, encrypted.",
    f2: "You approve every viewer, and every request to control your device.",
    f3: "No account, no ads, no tracking.",
    privacy: "Privacy policy",
    joinTitle: "Watch a screen",
    joinLead: "Someone invited you to watch their screen with ShareScreen.",
    joinLeadNamed: "{name} invited you to watch their screen with ShareScreen.",
    open: "Open in ShareScreen",
    room: "Room code",
    pin: "PIN",
    manual: "In the app, choose “Watch a screen” and enter the room code and PIN.",
    noApp: "Don’t have the app yet?",
  },
  tr: {
    lead: "Bir Android ekranını sesiyle paylaş; arkadaşın aynı Wi-Fi'da ya da internet üzerinden izlesin, hatta kontrol etsin.",
    download: "Android için indir",
    install: "Ekran paylaşır, izler ve diğer cihazları kontrol eder. Android 10 veya üzeri. Telefonun bu kaynaktan uygulama yüklemek için izin isteyecek.",
    downloadFull: "Tam sürümü indir",
    installFull: "Bu cihazın başka cihazlardan kontrol edilmesine de izin verir. Erişilebilirlik hizmeti kullandığı için bazı telefonlarda Google Play Protect tarafından engellenir.",
    f1: "Görüntü ve ses, şifreli olarak doğrudan cihazlar arasında akar.",
    f2: "Her izleyiciyi ve cihazını kontrol etme isteklerini sen onaylarsın.",
    f3: "Hesap yok, reklam yok, izleme yok.",
    privacy: "Gizlilik politikası",
    joinTitle: "Bir ekranı izle",
    joinLead: "Biri seni ShareScreen ile ekranını izlemeye davet etti.",
    joinLeadNamed: "{name} seni ShareScreen ile ekranını izlemeye davet etti.",
    open: "ShareScreen'de aç",
    room: "Oda kodu",
    pin: "PIN",
    manual: "Uygulamada “Bir ekranı izle”yi seçip oda kodunu ve PIN'i gir.",
    noApp: "Uygulaman yok mu?",
  },
};

window.applyLanguage = function applyLanguage() {
  const language = (navigator.language || "en").toLowerCase().startsWith("tr") ? "tr" : "en";
  document.documentElement.lang = language;
  for (const element of document.querySelectorAll("[data-t]")) {
    let key = element.dataset.t;
    if (element.dataset.name && key === "joinLead") key = "joinLeadNamed";
    const text = TEXT[language][key];
    if (text) element.textContent = text.replace("{name}", element.dataset.name || "");
  }
};
window.applyLanguage();
