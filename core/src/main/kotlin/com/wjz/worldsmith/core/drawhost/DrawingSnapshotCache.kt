package com.wjz.worldsmith.core.drawhost

import com.wjz.worldsmith.core.draw.DrawStructure

/** Conservative resident-size accounting. Oversize drawings still work, without being retained. */
class DrawingSnapshotCache(private val maximum:Long=128L*1024*1024) {
    private val entries=LinkedHashMap<String,Pair<DrawStructure,Long>>(16,0.75f,true)
    private var bytes=0L
    @Synchronized fun get(key:String,loader:()->DrawStructure):DrawStructure {
        entries[key]?.let {return it.first}
        val value=loader();val weight=value.voxels().size*192L+value.anchors().size*256L+1024
        if(weight<=maximum){while(entries.isNotEmpty()&&bytes+weight>maximum){val first=entries.entries.first();bytes-=first.value.second;entries.remove(first.key)};entries[key]=value to weight;bytes+=weight}
        return value
    }
}
