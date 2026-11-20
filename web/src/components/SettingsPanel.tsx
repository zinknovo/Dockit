'use client'

import { SettingsForm } from './SettingsForm'
import { useTranslations } from 'next-intl'

export function SettingsPanel({ open, onClose }: { open: boolean; onClose: () => void }) {
  const t = useTranslations('settings')
  const tCommon = useTranslations('common')

  return (
    <>
      <div
        className={`position-fixed top-0 start-0 end-0 bottom-0 z-40 bg-black-30 transition ${open ? 'opacity-100' : 'pe-none opacity-0'}`}
        onClick={onClose}
      />
      <aside
        className={`position-fixed end-0 top-0 z-50 h-100 w-100 max-w-sm border-start border-border bg-box p-4 shadow-lg transition ${
          open ? 'translate-x-0' : 'translate-x-full'
        }`}
      >
        <div className="mb-4 d-flex align-items-center justify-content-between">
          <h2 className="text-lg fw-semibold text-g-900">{t('panelTitle')}</h2>
          <button
            onClick={onClose}
            className="rounded-pill border border-border px-2 py-1 text-xs text-g-600"
          >
            {tCommon('close')}
          </button>
        </div>
        <SettingsForm />
      </aside>
    </>
  )
}
