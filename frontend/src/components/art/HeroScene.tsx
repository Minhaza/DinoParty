import landscape from '@/assets/backgrounds/hero-canyon.png'
import { assetUrl } from '@/assets'
import { DinoArtwork } from '@/components/art/DinoArtwork'

/** Ground characters share the grassy bank; only the flying Ptera moves vertically. */
export function HeroScene() {
  return (
    <div
      aria-hidden="true"
      className="pointer-events-none absolute inset-0 -z-10 overflow-hidden bg-[#f9d8ab]"
    >
      <img
        src={landscape}
        alt=""
        draggable={false}
        className="absolute inset-0 size-full object-cover object-bottom lg:object-fill"
      />
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_50%_24%,rgb(255_249_230/0.45),transparent_60%)]" />

      <DinoArtwork
        avatarKey="dino-ptera"
        className="absolute right-[9%] top-[12%] hidden w-[12vw] max-w-[210px] animate-float md:block"
      />

      <div className="absolute bottom-[16%] left-[9%] hidden w-[25vw] max-w-[400px] lg:block">
        <span className="absolute bottom-[7%] left-[13%] h-[5%] w-[58%] rounded-[50%] bg-[#283519]/35 blur-[6px]" />
        <DinoArtwork avatarKey="dino-rex" className="relative w-full" />
      </div>
      <div className="absolute bottom-[14%] right-[7%] hidden w-[25vw] max-w-[400px] lg:block">
        <span className="absolute bottom-[15%] left-[14%] h-[5%] w-[65%] rounded-[50%] bg-[#283519]/35 blur-[6px]" />
        <DinoArtwork avatarKey="dino-tricera" className="relative w-full" />
      </div>
      <div className="absolute inset-0 hidden bg-[#0f1a30]/50 dark:block" />
    </div>
  )
}
/** Quiet page background for app screens: the soft painted plate when present, else a plain wash. */
export function PageBackdrop() {
  const url = assetUrl('backgrounds/page-soft')
  return (
    <div
      aria-hidden="true"
      className="pointer-events-none fixed inset-0 -z-10 bg-[linear-gradient(180deg,#e7f3fb_0%,#fff9f0_45%,#ffeedb_100%)] dark:bg-background"
    >
      {url && (
        <img
          src={url}
          alt=""
          decoding="async"
          className="size-full object-cover opacity-60 dark:opacity-25"
        />
      )}
    </div>
  )
}
