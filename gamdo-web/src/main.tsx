import { useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './style.css'

type ShootConfig = { maxPhotos: number; policy: Record<string, unknown> }

function ShootPage({ token }: { token: string }) {
  const video = useRef<HTMLVideoElement>(null)
  const [config, setConfig] = useState<ShootConfig | null>(null)
  const [message, setMessage] = useState('카메라를 준비하는 중')
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
      setMessage('직접 셔터를 눌러 촬영하세요')
    }).catch(() => setMessage('이 촬영 링크는 만료되었거나 카메라를 사용할 수 없어요.'))
    return () => stream?.getTracks().forEach(track => track.stop())
  }, [token])

  async function takePhoto() {
    if (!video.current || !config || count >= config.maxPhotos) return
    const canvas = document.createElement('canvas')
    canvas.width = video.current.videoWidth; canvas.height = video.current.videoHeight
    canvas.getContext('2d')?.drawImage(video.current, 0, 0)
    setMessage('사진을 보내는 중')
    const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.92))
    if (!blob) return setMessage('사진을 만들지 못했어요.')
    const form = new FormData(); form.append('image', blob, 'gamdo-shoot.jpg')
    const response = await fetch(`/api/v1/shoot-upload/${token}`, { method: 'POST', body: form })
    if (!response.ok) return setMessage('전송하지 못했어요. 다시 시도해 주세요.')
    setCount(value => value + 1); setMessage('감도 앱으로 사진을 보냈어요')
  }

  const policy = config?.policy ?? {}
  return <main className="camera">
    <video ref={video} autoPlay playsInline muted />
    <div className="shade top"><strong>감도 · 나 찍어줘</strong><span>{count}/{config?.maxPhotos ?? 5}</span></div>
    <div className="guide"><div className="bracket" /><p>{policy.zoom ? `${policy.zoom}배 줌으로` : '원하는 구도로'} 직접 맞춰주세요</p></div>
    <div className="shade bottom"><p>{message}</p><button aria-label="촬영" onClick={takePhoto} disabled={!config || count >= config.maxPhotos}><i /></button></div>
  </main>
}

function ArchivePage() {
  return <main className="archive"><small>DEMO</small><h1>감도 아카이브</h1><p>내가 좋아하는 사진의 기준은 시간이 지나며 바뀝니다.</p><section><article><b>2025 감도</b><span>따뜻한 색감 · 정돈된 구도</span></article><article><b>2026 감도</b><span>플래시 · 강한 대비 · 조금 더 즉흥적으로</span></article></section><h2>같은 장면, 다른 감도</h2><div className="compare"><div>내 감도<br/><em>자연광 · 여백</em></div><div>데모 감도<br/><em>플래시 · 대비</em></div></div><p className="note">표시된 기록과 비교는 시연용 샘플 데이터입니다.</p></main>
}

const path = window.location.pathname
createRoot(document.getElementById('root')!).render(path.startsWith('/shoot/') ? <ShootPage token={path.split('/').pop()!} /> : <ArchivePage />)
