import { NextRequest, NextResponse } from 'next/server'
import { readFile } from 'fs/promises'
import path from 'path'

export const dynamic = 'force-dynamic'

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
  minSdk?: number
  targetSdk?: number
}

interface ReleasesFile {
  repo: string
  latest: ReleaseEntry
  history: ReleaseEntry[]
}

/**
 * 站点对外根地址。
 * ⚠ 不能从请求头推导：平台边缘代理转发时携带的是内部 fcapp.run 域名，
 * 会被拼进 apkUrl 下发给 App，导致应用内更新「连接失败」（v1.6.1 修复）。
 * 发布中心对外域名固定，直接使用；本地开发可设 PUBLIC_BASE_URL 覆盖。
 */
function siteOrigin(_req: NextRequest): string {
  return process.env.PUBLIC_BASE_URL || 'https://dut-runmate.space-z.ai'
}

export async function GET(req: NextRequest) {
  try {
    const file = await readFile(path.join(process.cwd(), 'src/data/releases.json'), 'utf-8')
    const data = JSON.parse(file) as ReleasesFile
    const origin = siteOrigin(req)
    const { repo, latest, history } = data

    return NextResponse.json(
      {
        ok: true,
        repo,
        latest: {
          versionName: latest.versionName,
          versionCode: latest.versionCode,
          date: latest.date,
          notes: latest.notes,
          apkFile: latest.apkFile,
          apkPath: `/apk/${latest.apkFile}`,
          apkUrl: `${origin}/apk/${latest.apkFile}`,
          sha256: latest.sha256,
          sizeBytes: latest.sizeBytes,
          minSdk: latest.minSdk ?? 24,
          github: {
            releaseUrl: latest.githubReleaseUrl,
            apkUrl: latest.githubApkUrl,
          },
        },
        history: history.map((h2) => ({
          versionName: h2.versionName,
          versionCode: h2.versionCode,
          date: h2.date,
          notes: h2.notes,
          apkPath: `/apk/${h2.apkFile}`,
          sha256: h2.sha256,
          sizeBytes: h2.sizeBytes,
          github: { releaseUrl: h2.githubReleaseUrl, apkUrl: h2.githubApkUrl },
        })),
      },
      { headers: { 'Cache-Control': 'no-store' } }
    )
  } catch (e) {
    return NextResponse.json(
      { ok: false, error: e instanceof Error ? e.message : 'failed to read releases' },
      { status: 500, headers: { 'Cache-Control': 'no-store' } }
    )
  }
}
