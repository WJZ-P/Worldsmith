import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** Converts real packed UV islands into a deterministic TextureRecipe. No image service or painting dependency. */
export function makeCreatureTexture(layout) {
  if (!Number.isInteger(layout.textureWidth) || !Number.isInteger(layout.textureHeight) || !Array.isArray(layout.islands)) throw new Error('A compiled CreatureUvLayout is required');
  const palette=['#00000000','#293442FF','#4B5B6EFF','#69778BFF','#1D2531FF','#775737FF','#AD8551FF','#D4B879FF','#455550FF',
    '#68408EFF','#A66DD5FF','#D5AAF5FF','#F0DEFFFF','#AEBDBFFF','#DFE6DCFF','#829699FF','#B9A476FF','#E0CFA1FF','#292F36FF','#99D9D5FF','#EDF9E8FF'];
  const roles={stone_dark:[1,2,4,3],stone_engraved:[1,2,4,10],basalt:[1,2,4,3],bronze:[5,6,8,7],old_iron:[2,3,8,13],
    amethyst:[9,10,11,12],crystal:[9,10,11,12],moon_glow:[19,20,11,20],fur_grey:[15,13,2,14],fur_white:[13,14,15,20],antler:[16,17,5,17],hoof:[18,4,2,3]};
  const operations=[{kind:'fill',color:0}];
  const line=(x,y,x2,y2,color)=>operations.push({kind:'line',x,y,x2,y2,color});
  for (const island of layout.islands) {
    if (island.sharedWith) continue;
    const p=roles[island.materialRole]??roles.stone_dark;
    const x=island.uv.u,y=island.uv.v,w=island.width,h=island.height;
    operations.push({kind:'fill',x,y,width:w,height:h,color:p[0]});
    operations.push({kind:'noise',x,y,width:w,height:h,colors:[p[1],p[2]],probability:island.materialRole.startsWith('fur')?.11:.18});
    const face=island.faces.find(f=>f.face==='front');
    if (!face) throw new Error('Native front UV face missing: '+island.id);
    line(face.x,face.y,face.x+face.width-1,face.y,p[3]);
    if(face.height>1)line(face.x,face.y,face.x,face.y+face.height-1,p[2]);
    if(['amethyst','crystal','moon_glow'].includes(island.materialRole))
      line(face.x,face.y,face.x+face.width-1,face.y+face.height-1,p[3]);
    if(island.materialRole==='stone_engraved'&&face.width>=5&&face.height>=5){
      const cx=face.x+Math.floor(face.width/2),cy=face.y+Math.floor(face.height/2);
      line(cx,face.y+1,cx,face.y+face.height-2,10);
      line(Math.max(face.x+1,cx-2),cy,Math.min(face.x+face.width-2,cx+2),cy,10);
    }
  }
  if(operations.length>256)throw new Error(`UV recipe needs ${operations.length} operations; simplify the actual model instead of dropping islands`);
  return {schemaVersion:1,width:layout.textureWidth,height:layout.textureHeight,palette,seed:20260910,operations};
}

if (process.argv[1] && path.resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  if(process.argv.length!==4)throw new Error('Usage: node make-creature-texture.mjs <real-uv-layout.json> <output-texture-recipe.json>');
  const layout=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
  const output=path.resolve(process.argv[3]);fs.mkdirSync(path.dirname(output),{recursive:true});
  fs.writeFileSync(output,JSON.stringify(makeCreatureTexture(layout),null,2)+'\n');
  console.log(output);
}
