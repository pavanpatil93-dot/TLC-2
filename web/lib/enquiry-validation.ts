export function validateEnquiry(input:unknown){
 if(!input||typeof input!=="object"||Array.isArray(input))return null;
 const x=input as Record<string,unknown>;
 const read=(k:string,max:number)=>typeof x[k]==="string"&&(x[k] as string).trim().length<=max?(x[k] as string).trim():"";
 const name=read("name",120),phone=read("phone",18),requirement=read("requirement",2000),propertyRef=read("propertyRef",150),location=read("location",160),captcha=read("captchaToken",1000);
 if(name.length<2||!/^\+[1-9][0-9]{7,14}$/.test(phone)||requirement.length<5||x.consent!==true||captcha.length<10)return null;
 return {name,phone,requirement,propertyRef,location,captcha};
}
