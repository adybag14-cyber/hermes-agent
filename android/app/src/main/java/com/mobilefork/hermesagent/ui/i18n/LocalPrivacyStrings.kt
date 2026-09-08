package com.mobilefork.hermesagent.ui.i18n

object LocalPrivacyText {
    fun policy(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> "Read the privacy policy"
        AppLanguage.CHINESE -> "阅读隐私政策"
        AppLanguage.SPANISH -> "Leer la política de privacidad"
        AppLanguage.GERMAN -> "Datenschutzerklärung lesen"
        AppLanguage.PORTUGUESE -> "Ler a política de privacidade"
        AppLanguage.FRENCH -> "Lire la politique de confidentialité"
    }
    fun delete(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> "Delete all local app data"
        AppLanguage.CHINESE -> "删除全部本地应用数据"
        AppLanguage.SPANISH -> "Eliminar todos los datos locales de la app"
        AppLanguage.GERMAN -> "Alle lokalen App-Daten löschen"
        AppLanguage.PORTUGUESE -> "Excluir todos os dados locais do app"
        AppLanguage.FRENCH -> "Supprimer toutes les données locales de l’app"
    }
    fun warning(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> "This permanently erases this app’s private chats, models, files, settings, saved credentials, consent records and report deletion receipts. Android will close and reset the app. Exported files and shared folders are not erased. Delete submitted reports first if you want to use their receipts; resetting does not contact the report service. This does not delete third-party accounts or data already sent to providers. Cancel keeps your data."
        AppLanguage.CHINESE -> "这将永久删除此应用私有的聊天、模型、文件、设置、已保存凭据、同意记录及举报删除凭据。Android 将关闭并重置应用。不会删除已导出的文件或共享文件夹。若要使用举报凭据，请先删除已提交的举报；重置不会联系举报服务。这不会删除第三方账户或已发给服务商的数据。取消将保留数据。"
        AppLanguage.SPANISH -> "Se borran permanentemente chats, modelos, archivos privados, ajustes, credenciales, consentimientos y recibos de denuncias de esta app. Android la cerrará y restablecerá. No se borran archivos exportados ni carpetas compartidas. Elimina primero las denuncias si quieres usar sus recibos; restablecer no contacta ese servicio. No elimina cuentas externas ni datos ya enviados a proveedores. Cancelar conserva tus datos."
        AppLanguage.GERMAN -> "Dies löscht dauerhaft private Chats, Modelle, Dateien, Einstellungen, Zugangsdaten, Zustimmungen und Melde-Löschbelege dieser App. Android schließt und setzt die App zurück. Exporte und gemeinsame Ordner bleiben. Meldungen zuerst mit ihren Belegen löschen; Zurücksetzen kontaktiert den Meldedienst nicht. Konten anderer Anbieter und bereits gesendete Daten werden nicht gelöscht. Abbrechen behält Ihre Daten."
        AppLanguage.PORTUGUESE -> "Isso apaga permanentemente chats, modelos, arquivos privados, configurações, credenciais, consentimentos e recibos de denúncias deste app. O Android fechará e redefinirá o app. Arquivos exportados e pastas compartilhadas permanecem. Exclua denúncias primeiro se quiser usar os recibos; redefinir não contata esse serviço. Não exclui contas externas nem dados já enviados a provedores. Cancelar mantém seus dados."
        AppLanguage.FRENCH -> "Cela efface définitivement les chats, modèles, fichiers privés, paramètres, identifiants, consentements et reçus de signalement de cette app. Android la fermera et la réinitialisera. Les exports et dossiers partagés restent. Supprimez d’abord les signalements avec leurs reçus ; la réinitialisation ne contacte pas ce service. Les comptes tiers et données déjà envoyées ne sont pas supprimés. Annuler conserve vos données."
    }
    fun accountScope(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> "Hermes has no publisher-hosted user account. Provider sign-out and API-key removal affect local access only. Use your provider’s website to revoke credentials or delete its account and data. Local erasure cannot delete a provider account."
        AppLanguage.CHINESE -> "Hermes 没有发布者托管的用户账户。退出服务商登录或移除 API 密钥仅影响本地访问。请到服务商网站撤销凭据或删除其账户和数据。本地删除无法删除服务商账户。"
        AppLanguage.SPANISH -> "Hermes no aloja cuentas de usuario del editor. Cerrar sesión o quitar claves solo afecta al acceso local. Revoca credenciales o elimina cuentas y datos en la web del proveedor. El borrado local no elimina cuentas externas."
        AppLanguage.GERMAN -> "Hermes betreibt keine Herausgeber-Nutzerkonten. Abmeldung und Schlüsselentfernung betreffen nur lokalen Zugriff. Zugangsdaten, Konten und Daten auf der Website des Anbieters widerrufen oder löschen. Lokales Löschen entfernt keine Anbieterkonten."
        AppLanguage.PORTUGUESE -> "Hermes não hospeda contas de usuário do editor. Sair ou remover chaves afeta apenas o acesso local. Revogue credenciais ou exclua contas e dados no site do provedor. Exclusão local não apaga contas externas."
        AppLanguage.FRENCH -> "Hermes n’héberge pas de compte utilisateur de l’éditeur. Déconnexion et retrait de clé ne concernent que l’accès local. Révoquez les identifiants ou supprimez comptes et données sur le site du fournisseur. L’effacement local ne supprime pas les comptes tiers."
    }
    fun deletionDenied(language: AppLanguage): String = when (language) {
        AppLanguage.ENGLISH -> "Android did not accept the reset. Use Android Settings → Apps → Hermes → Storage to manage local data."
        AppLanguage.CHINESE -> "Android 未接受重置。请在 Android 设置 → 应用 → Hermes → 存储中管理本地数据。"
        AppLanguage.SPANISH -> "Android no aceptó el borrado. Gestiona los datos en Ajustes de Android → Apps → Hermes → Almacenamiento."
        AppLanguage.GERMAN -> "Android hat das Zurücksetzen nicht angenommen. Daten unter Android-Einstellungen → Apps → Hermes → Speicher verwalten."
        AppLanguage.PORTUGUESE -> "O Android não aceitou a redefinição. Gerencie os dados em Configurações do Android → Apps → Hermes → Armazenamento."
        AppLanguage.FRENCH -> "Android n’a pas accepté la réinitialisation. Gérez les données dans Paramètres Android → Applications → Hermes → Stockage."
    }
}
