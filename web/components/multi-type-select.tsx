"use client";
import {useState} from "react";
export const PROPERTY_TYPES=[{value:"office",label:"Office"},{value:"shop",label:"Shop"},{value:"showroom",label:"Showroom"},{value:"warehouse",label:"Warehouse"}] as const;
export function MultiTypeSelect({value,onChange,id="property-types"}:{value:string[];onChange:(next:string[])=>void;id?:string}){
 const [open,setOpen]=useState(false);
 const all=value.length===0;
 const text=all?"All property types":value.length===1?PROPERTY_TYPES.find(t=>t.value===value[0])?.label||"1 selected":value.length+" types selected";
 function toggle(type:string){onChange(value.includes(type)?value.filter(x=>x!==type):[...value,type]);}
 return <div className="multi-type" id={id}><button type="button" className="multi-type-trigger" aria-expanded={open} aria-controls={id+"-options"} onClick={()=>setOpen(!open)}>{text}<span aria-hidden="true">{open?"▴":"▾"}</span></button>{open&&<div className="multi-type-options" id={id+"-options"}><label className="multi-type-option"><input type="checkbox" checked={all} onChange={()=>onChange([])}/><span>All property types</span></label>{PROPERTY_TYPES.map(t=><label key={t.value} className="multi-type-option"><input type="checkbox" checked={value.includes(t.value)} onChange={()=>toggle(t.value)}/><span>{t.label}</span></label>)}<button type="button" className="multi-type-done" onClick={()=>setOpen(false)}>Done</button></div>}</div>
}
