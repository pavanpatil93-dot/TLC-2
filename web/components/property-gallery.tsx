"use client";
import {useState} from "react";
export default function PropertyGallery({isDemo=false}:{isDemo?:boolean}){
 const [active,setActive]=useState(0);
 const slides=[{label:"Building overview",className:"a"},{label:"Office / interior view",className:"b"},{label:"Space and amenities",className:"c"}];
 return <div className="details-gallery" aria-label="Property photography preview"><div className="gallery-main"><div className={"facade photo-facade "+slides[active].className} aria-hidden="true"/><span className="media-pill">{isDemo?"DESIGN SAMPLE — NO REAL UNIT":"ILLUSTRATIVE ONLY · PHOTOS NOT APPROVED"}</span><div className="gallery-bottom"><span>{slides[active].label} · Illustrative image, not this property</span><span>{active+1} / {slides.length}</span></div></div><div className="gallery-thumbs">{slides.map((slide,i)=><button key={slide.label} type="button" className={"gallery-thumb "+(active===i?"selected":"")} onClick={()=>setActive(i)} aria-label={"Show illustrative "+slide.label} aria-pressed={active===i}><div className={"facade photo-facade "+slide.className} aria-hidden="true"/></button>)}</div></div>
}
