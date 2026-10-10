'use client'

import { useSyncExternalStore, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Check, Copy } from 'lucide-react'

const subscribe = () => () => {}

/** 「App 内更新配置」卡片：v1.5.0 起服务器地址内置；此处供旧版本（≤1.4.x）手动填写 */
export function ConfigCard({ apiPath }: { apiPath: string }) {
  // SSR 安全地读取 window.location.origin（避免在 effect 中 setState）
  const origin = useSyncExternalStore(
    subscribe,
    () => window.location.origin,
    () => ''
  )
  const [copied, setCopied] = useState(false)
  const [copiedApi, setCopiedApi] = useState(false)

  const apiUrl = origin ? `${origin}${apiPath}` : ''

  const copy = async (text: string, which: 'site' | 'api') => {
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      const ta = document.createElement('textarea')
      ta.value = text
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
    }
    if (which === 'site') {
      setCopied(true)
      setTimeout(() => setCopied(false), 1600)
    } else {
      setCopiedApi(true)
      setTimeout(() => setCopiedApi(false), 1600)
    }
  }

  return (
    <div className="space-y-4">
      <div className="space-y-2">
        <p className="text-sm font-medium text-foreground">① 更新检查地址（App 设置 → 更新 → 更新服务器地址）</p>
        <div className="flex gap-2">
          <Input readOnly value={apiUrl || '加载中…'} className="font-mono text-xs" />
          <Button
            variant="outline"
            size="icon"
            onClick={() => apiUrl && copy(apiUrl, 'api')}
            aria-label="复制更新检查地址"
          >
            {copiedApi ? <Check className="h-4 w-4 text-emerald-600" /> : <Copy className="h-4 w-4" />}
          </Button>
        </div>
        <p className="text-xs text-muted-foreground">
          v1.5.0 起已内置本站地址、无需填写；旧版本填入后同样优先走本站（国内直连速度快）。
        </p>
      </div>

      <div className="space-y-2">
        <p className="text-sm font-medium text-foreground">② 站点地址（备用，含历史版本直链）</p>
        <div className="flex gap-2">
          <Input readOnly value={origin} className="font-mono text-xs" />
          <Button
            variant="outline"
            size="icon"
            onClick={() => origin && copy(origin, 'site')}
            aria-label="复制站点地址"
          >
            {copied ? <Check className="h-4 w-4 text-emerald-600" /> : <Copy className="h-4 w-4" />}
          </Button>
        </div>
      </div>

      <ol className="list-decimal space-y-1 pl-5 text-xs leading-relaxed text-muted-foreground">
        <li>v1.5.0+：无需任何操作，App 自动从本站检查更新（每天一次）</li>
        <li>旧版本（≤1.4.x）：手机上打开本页 → 复制上方地址 → App 设置 → 更新 → 粘贴</li>
        <li>点「立即检查更新」验证连通，之后 App 每天自动检查</li>
      </ol>
    </div>
  )
}
