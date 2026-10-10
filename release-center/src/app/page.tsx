import { readFile } from 'fs/promises'
import path from 'path'
import QRCode from 'qrcode'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Separator } from '@/components/ui/separator'
import { ConfigCard } from '@/components/runmate/ConfigCard'
import { Download, Github, MapPin, RefreshCw, Smartphone, Volume2 } from 'lucide-react'

interface ReleaseEntry {
  versionName: string
  versionCode: number
  date: string
  apkFile: string
  sha256: string
  sizeBytes: number
  notes: string
  githubTag: string
  githubReleaseUrl: string
  githubApkUrl: string
}

interface ReleasesFile {
  repo: string
  latest: ReleaseEntry
  history: ReleaseEntry[]
}

function fmtSize(bytes: number): string {
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

export default async function Home() {
  const data: ReleasesFile = JSON.parse(
    await readFile(path.join(process.cwd(), 'src/data/releases.json'), 'utf-8')
  )
  const { repo, latest, history } = data
  const apkPath = `/apk/${latest.apkFile}`
  // v1.5.0 起更新服务器地址已内置为本站固定域名（dut-runmate.space-z.ai）；
  // QR 直接编码本站 APK 直链，扫码即达（githubApkUrl 字段承载该直链，GitHub 仅作兜底）
  const qrTarget = latest.githubApkUrl || `https://dut-runmate.space-z.ai${apkPath}`
  const qr = await QRCode.toDataURL(qrTarget, {
    width: 220,
    margin: 1,
    color: { dark: '#064e3b', light: '#ffffff' },
  })

  return (
    <div className="min-h-screen flex flex-col bg-gradient-to-b from-emerald-50/60 via-background to-background">
      <header className="border-b bg-background/80 backdrop-blur">
        <div className="mx-auto flex max-w-3xl items-center justify-between px-4 py-4">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-600 text-white">
              <Volume2 className="h-5 w-5" aria-hidden />
            </div>
            <div>
              <h1 className="text-lg font-bold leading-tight">跑伴 RunMate</h1>
              <p className="text-xs text-muted-foreground">大工健康长跑打卡提醒 · 发布中心</p>
            </div>
          </div>
          <a href={`https://github.com/${repo}`} target="_blank" rel="noreferrer">
            <Button variant="outline" size="sm">
              <Github className="mr-1 h-4 w-4" aria-hidden /> GitHub
            </Button>
          </a>
        </div>
      </header>

      <main className="mx-auto w-full max-w-3xl flex-1 space-y-6 px-4 py-6">
        {/* 功能特性 */}
        <section aria-label="功能特性" className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          {[
            { icon: Volume2, title: '耳机语音提醒', desc: '接近预告 · 打卡播报 · 漏卡强提醒' },
            { icon: MapPin, title: '双模式录入点位', desc: '现场 GPS 标定 / 高德地图深放大选点' },
            { icon: RefreshCw, title: '应用内自更新', desc: '本站直连 或 GitHub Release 双通道' },
          ].map((f) => (
            <Card key={f.title} className="p-4">
              <f.icon className="mb-2 h-5 w-5 text-emerald-600" aria-hidden />
              <p className="text-sm font-semibold">{f.title}</p>
              <p className="mt-1 text-xs text-muted-foreground">{f.desc}</p>
            </Card>
          ))}
        </section>

        {/* 最新版本 */}
        <Card>
          <CardHeader className="pb-3">
            <div className="flex flex-wrap items-center gap-2">
              <CardTitle className="text-base">最新版本</CardTitle>
              <Badge className="bg-emerald-600 hover:bg-emerald-600">v{latest.versionName}</Badge>
              <Badge variant="outline">versionCode {latest.versionCode}</Badge>
              <span className="ml-auto text-xs text-muted-foreground">{latest.date}</span>
            </div>
            <CardDescription>
              Android 7.0+ · {fmtSize(latest.sizeBytes)} · minSdk {24} / targetSdk 34
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex flex-col gap-4 sm:flex-row">
              <div className="min-w-0 flex-1">
                <p className="whitespace-pre-wrap text-sm leading-relaxed">{latest.notes}</p>
                <p className="mt-3 break-all font-mono text-[10px] text-muted-foreground">
                  SHA256: {latest.sha256}
                </p>
              </div>
              <div className="flex shrink-0 flex-col items-center gap-2">
                <img src={qr} alt="APK 下载二维码（GitHub 直链）" className="h-32 w-32 rounded-lg border" />
                <span className="text-[10px] text-muted-foreground">扫码下载 APK</span>
              </div>
            </div>
            <Separator />
            <div className="flex flex-wrap gap-2">
              <a href={apkPath} download>
                <Button className="bg-emerald-600 hover:bg-emerald-700">
                  <Download className="mr-1 h-4 w-4" aria-hidden /> 下载 APK（本站直连）
                </Button>
              </a>
              <a href={latest.githubReleaseUrl} target="_blank" rel="noreferrer">
                <Button variant="outline">
                  <Github className="mr-1 h-4 w-4" aria-hidden /> GitHub Release
                </Button>
              </a>
            </div>
          </CardContent>
        </Card>

        {/* App 内更新配置 */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="flex items-center gap-2 text-base">
              <Smartphone className="h-4 w-4 text-emerald-600" aria-hidden />
              App 内更新配置
            </CardTitle>
            <CardDescription>v1.5.0 起更新服务器已内置为本站（dut-runmate.space-z.ai），无需任何配置；≤1.4.x 旧版更新通道已失效，请直接下载最新 APK 覆盖安装</CardDescription>
          </CardHeader>
          <CardContent>
            <ConfigCard apiPath="/api/latest" />
          </CardContent>
        </Card>

        {/* 历史版本 */}
        <Card>
          <CardHeader className="pb-3">
            <CardTitle className="text-base">历史版本</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {history.map((h) => (
              <div key={h.versionCode} className="flex flex-wrap items-center gap-2 rounded-lg border p-3">
                <Badge variant="secondary">v{h.versionName}</Badge>
                <span className="text-xs text-muted-foreground">{h.date} · {fmtSize(h.sizeBytes)}</span>
                <div className="ml-auto flex gap-2">
                  <a href={`/apk/${h.apkFile}`} download>
                    <Button variant="ghost" size="sm" className="text-emerald-700">
                      本站下载
                    </Button>
                  </a>
                  <a href={h.githubReleaseUrl} target="_blank" rel="noreferrer">
                    <Button variant="ghost" size="sm">
                      GitHub
                    </Button>
                  </a>
                </div>
                <p className="w-full text-xs text-muted-foreground">{h.notes.split('\n')[0]}</p>
              </div>
            ))}
          </CardContent>
        </Card>
      </main>

      <footer className="mt-auto border-t bg-background">
        <div className="mx-auto max-w-3xl px-4 py-4 text-xs leading-relaxed text-muted-foreground">
          <p>
            跑伴 RunMate 是个人学习研究项目，与大连理工大学官方无关；仅为跑步提醒工具，不修改成绩、不模拟
            GPS。发布新版本：将 APK 放入 <code className="font-mono">public/apk/</code> 并更新{' '}
            <code className="font-mono">src/data/releases.json</code>。
          </p>
        </div>
      </footer>
    </div>
  )
}
