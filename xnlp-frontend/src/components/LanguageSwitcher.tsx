import { useTranslation } from 'react-i18next'

const languages = [
  { value: 'zh-CN', labelKey: 'language.zh' },
  { value: 'en', labelKey: 'language.en' },
]

export default function LanguageSwitcher() {
  const { i18n, t } = useTranslation()

  const changeLanguage = (value: string) => {
    localStorage.setItem('xnlp.language', value)
    i18n.changeLanguage(value)
  }

  return (
    <label className="flex items-center gap-2 px-3 py-2 text-xs text-gray-400 lg:px-4">
      <span className="shrink-0">{t('language.label')}</span>
      <select
        value={i18n.language}
        onChange={event => changeLanguage(event.target.value)}
        className="rounded-md border border-gray-700 bg-gray-900 px-2 py-1 text-xs text-gray-200 outline-none focus:border-emerald-500"
      >
        {languages.map(language => (
          <option key={language.value} value={language.value}>{t(language.labelKey)}</option>
        ))}
      </select>
    </label>
  )
}
