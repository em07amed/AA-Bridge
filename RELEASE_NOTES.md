## الجديد في النسخة دي

⚠️ **مهم: النسخة دي لازم تمسح القديمة قبلها مرة واحدة.** الـ app بقى متوقّع بمفتاح خاص، فمش هيتسطّب فوق النسخ القديمة. امسح Drive Bridge، سطّب دي، واعمل الـ setup تاني. التحديثات بعد كده هتتسطّب عادي.

**🔒 أمان**
- الـ app بقى متوقّع بمفتاح خاص بدل المفتاح العام، فمحدش يقدر يعمل نسخة مزيفة تتسطّب مكانه.
- نسخة release بدل debug: بيانات الـ app (الرادارات والحدود والسجلات) مبقتش تتقري من USB.

**🌡️ الحرارة والمدينة**
- درجة الحرارة برّه واسم المدينة على شاشة العربية فوق السرعة، وبيتحدّثوا كل 10 دقايق أو كل 5 كيلو.

**🔄 تحديثات AA Bridge 1.3.0**
- Android Auto مبقاش بيقفل بعد ما يعيد الاتصال لوحده، وإصلاحات لـ QDLink.
- صفحة دعم Ali Morgan صاحب AA Bridge الأصلي.

**⚡ أداء**
- الطرق المحفوظة على الموبايل بتتمسح بعد 30 يوم، وبحد أقصى 50 ميجا.
- العرض على شاشة العربية بيترسم بس لما حاجة تتغيّر.

---

## What's new

⚠️ **One-time reinstall:** releases are now signed with a private key, so this version won't install over older ones. Uninstall Drive Bridge, install this one, and run setup again.

- **Security:** private release signing key; release (non-debuggable) build.
- **Temperature and city** on the car screen.
- **AA Bridge 1.3.0** merged: Android Auto no longer exits after reconnecting on its own, QDLink fixes, and Ali Morgan's support page.
- **Performance:** tile cache pruned (30 days / 50 MB); the overlay redraws only on change.
