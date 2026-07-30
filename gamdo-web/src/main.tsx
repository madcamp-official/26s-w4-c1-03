import { useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './style.css'

type ShootSlot = {
  id: string
  role: 'PERSON' | 'OBJECT'
  visualKind: string
  bounds: { left: number; top: number; right: number; bottom: number }
  preferredAspectRatio: number
}

type ShootPolicy = {
  version?: number
  layoutId?: string
  slots?: ShootSlot[]
  preferredZoom?: number
  recommendedPhotos?: number
  zoom?: number
}

type ShootConfig = { maxPhotos: number; policy: ShootPolicy }

function GuideOverlay({ slots = [] }: { slots?: ShootSlot[] }) {
  return <svg className="guide-overlay" viewBox="0 0 1 1" preserveAspectRatio="none" aria-hidden="true">
    {slots.map(slot => {
      const { left, top, right, bottom } = slot.bounds
      const width = right - left
      const height = bottom - top
      return <g key={slot.id} className={slot.role === 'PERSON' ? 'person-guide' : 'object-guide'}>
        <rect x={left} y={top} width={width} height={height} rx={0.012} />
        <path d={`M ${left} ${top + height * .14} V ${top} H ${left + width * .14} M ${right - width * .14} ${top} H ${right} V ${top + height * .14} M ${left} ${bottom - height * .14} V ${bottom} H ${left + width * .14} M ${right - width * .14} ${bottom} H ${right} V ${bottom - height * .14}`} />
      </g>
    })}
  </svg>
}

function ShootPage({ token }: { token: string }) {
  const video = useRef<HTMLVideoElement>(null)
  const [config, setConfig] = useState<ShootConfig | null>(null)
  const [message, setMessage] = useState('')
  const [count, setCount] = useState(0)

  useEffect(() => {
    let stream: MediaStream | undefined
    fetch(`/api/v1/shoot-upload/${token}/config`).then(async response => {
      if (!response.ok) throw new Error('expired')
      return response.json() as Promise<ShootConfig>
    }).then(async value => {
      setConfig(value)
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: false })
      if (video.current) video.current.srcObject = stream
      setMessage('')
      const zoom = value.policy.preferredZoom ?? value.policy.zoom
      const track = stream?.getVideoTracks()[0]
      const capabilities = track?.getCapabilities?.() as MediaTrackCapabilities & { zoom?: { min: number; max: number; step?: number } } | undefined
      if (track && zoom && capabilities?.zoom) {
        const clamped = Math.min(capabilities.zoom.max, Math.max(capabilities.zoom.min, zoom))
        const constraints = { advanced: [{ zoom: clamped }] } as unknown as MediaTrackConstraints
        void track.applyConstraints(constraints).catch(() => undefined)
      }
    }).catch(() => setMessage('이 촬영 링크는 만료되었거나 카메라를 사용할 수 없어요.'))
    return () => stream?.getTracks().forEach(track => track.stop())
  }, [token])

  async function takePhoto() {
    if (!video.current || !config || count >= config.maxPhotos) return
    const canvas = document.createElement('canvas')
    canvas.width = video.current.videoWidth; canvas.height = video.current.videoHeight
    canvas.getContext('2d')?.drawImage(video.current, 0, 0)
    setMessage('전송 중')
    const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.92))
    if (!blob) return setMessage('사진을 만들지 못했어요.')
    const form = new FormData(); form.append('image', blob, 'gamdo-shoot.jpg')
    const response = await fetch(`/api/v1/shoot-upload/${token}`, { method: 'POST', body: form })
    if (!response.ok) return setMessage('전송하지 못했어요. 다시 시도해 주세요.')
    setCount(value => value + 1); setMessage('')
  }

  const policy = config?.policy ?? {}
  const slots = policy.slots ?? []
  const targetPhotos = Math.min(config?.maxPhotos ?? 5, policy.recommendedPhotos ?? 3)
  return <main className="camera">
    <video ref={video} autoPlay playsInline muted />
    <GuideOverlay slots={slots} />
    <div className="shade top"><strong>감도</strong><span className="shot-dots" aria-label={`${count}장 촬영됨`}>{Array.from({ length: targetPhotos }, (_, index) => <i key={index} className={index < count ? 'done' : ''} />)}</span></div>
    <div className="shade bottom"><span role="status" aria-live="polite">{message}</span><button aria-label="촬영" onClick={takePhoto} disabled={!config || count >= targetPhotos}><i /></button></div>
  </main>
}

function ArchivePage() {
  return <main className="archive"><small>DEMO</small><h1>감도 아카이브</h1><p>내가 좋아하는 사진의 기준은 시간이 지나며 바뀝니다.</p><section><article><b>2025 감도</b><span>따뜻한 색감 · 정돈된 구도</span></article><article><b>2026 감도</b><span>플래시 · 강한 대비 · 조금 더 즉흥적으로</span></article></section><h2>같은 장면, 다른 감도</h2><div className="compare"><div>내 감도<br/><em>자연광 · 여백</em></div><div>데모 감도<br/><em>플래시 · 대비</em></div></div><p className="note">표시된 기록과 비교는 시연용 샘플 데이터입니다.</p></main>
}

const path = window.location.pathname
createRoot(document.getElementById('root')!).render(path.startsWith('/shoot/') ? <ShootPage token={path.split('/').pop()!} /> : <ArchivePage />)
