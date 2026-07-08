import { useTranslation } from 'react-i18next'

export default function LanguageSwitcher() {
  const { i18n, t } = useTranslation()

  const currentLanguage = i18n.language?.startsWith('en') ? 'en' : 'zh-CN'
  const nextLanguage = currentLanguage === 'zh-CN' ? 'en' : 'zh-CN'

  const toggleLanguage = () => {
    localStorage.setItem('xnlp.language', nextLanguage)
    i18n.changeLanguage(nextLanguage)
  }

  return (
    <button
      type="button"
      onClick={toggleLanguage}
      className="shrink-0 rounded-md border border-gray-200 px-3 py-1.5 text-xs font-medium text-gray-600 transition-colors hover:border-gray-300 hover:bg-gray-50 hover:text-gray-900"
      title={t('language.label')}
    >
      {currentLanguage === 'zh-CN' ? t('language.zh') : t('language.en')}
    </button>
  )
}
