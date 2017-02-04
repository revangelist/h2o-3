package water.fvec;

import water.*;
import water.parser.BufferedString;
import water.util.ArrayUtils;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Created by tomas on 10/5/16.
 */
public class ChunkAry<C extends Chunk> extends Iced {
  public final VecAry _vec;
  public final long _start;
  public final int _len; // numrows in this chunk
  public final int _numCols;
  public final int _cidx;

  private transient BitSet _changedColsBitset;
  C [] _cs;
  int [] _ids;

  public ChunkAry(VecAry v,int cidx, C c){
    _vec = v;
    _cidx = cidx;
    _start = _vec.chunk2StartElem(cidx);
    _len = (int)(_vec.chunk2StartElem(cidx+1) - _start);
    _numCols = v.numCols();
    _ids = null;
    _cs = (C[])new Chunk[]{c};
  }
  public ChunkAry(VecAry v,int cidx, C... cs){this(v,cidx,cs,null);}
  public ChunkAry(int cidx, int len, C... cs){
    _vec = null;
    _cidx = cidx;
    _start = -1;
    _len = len;
    _numCols = cs.length;
    _cs = cs;
    _ids = null;
  }
  public ChunkAry(VecAry v,int cidx, C [] cs, int [] ids){
    _vec = v;
    _cidx = cidx;
    _start = _vec.chunk2StartElem(cidx);
    _len = (int)(_vec.chunk2StartElem(cidx+1) - _start);
    _numCols = v.numCols();
    _cs = cs;
    _ids = ids;
  }

  public ChunkAry nextChunk(){
    return _cidx +1 == _vec.nChunks()?null:_vec.chunkForChunkIdx(_cidx+1);
  }

  public int byteSize(){
    int s = 0;
    for(Chunk c:_cs)
      s += c.byteSize();
    return s;
  }

  public Chunk[] getSparseChunks() {return _cs;}


  public static final class SparseVec {
    Chunk _c;
    public int _colId;

    public SparseVec(){}
    public SparseVec(Chunk c, int col){
      _c = c;
      _colId = col;
    }
    public int colId(){return _colId;}
    public int sparseLen(){return _c.sparseLenZero();}

    public int getDoubles(double [] vals, int [] ids){
      return getDoubles(vals,ids,Double.NaN);
    }
    public int getDoubles(double [] vals, int [] ids, double NA){
      return _c.asSparseDoubles(vals,ids,NA);
    }
    public double[] getDoubles(double [] vals, double NA){
      return _c.getDoubles(vals,0,vals.length,NA);
    }
    public boolean isSparseZero(){
      return _c.isSparseZero();
    }

    public boolean isSparseNA() {
      return _c.isSparseNA();
    }

    public int[] getIntegers(int[] vals, int i) {
      _c.getIntegers(vals,0,vals.length,i);
      return vals;
    }
  }
  boolean _dense;

  public SparseVec sparseVec(int id){
    return null;
  }

  public SparseVec next(SparseVec sv) {
    if(sv._colId == _numCols) return sv;
    if(sv._colId > _numCols) throw new IndexOutOfBoundsException(sv._colId + " > " + _numCols);
    if(_dense){
      if(++sv._colId < _cs.length)
        sv._c = _cs[sv._colId];
      else sv._c = null;
      return sv;
    }
    int i = sv._colId;
    while(i < _numCols){
      int c = _vec.colFilter(sv._colId);
      if (c < lb || ub <= c) {
      }
      c = c - lb;
      Chunk chk = db.getColSparse(c, null);
      if (chk != null) {
        sv._c = chk;
        sv._colId = i;
        return sv;
      }
      i++;
    }
    sv._colId = i;
    sv._c = null;
    return sv;
  }

  public double[] getDoubles(int c, double [] vals){
    return getDoubles(c,vals,0,_len,Double.NaN);
  }
  public double[] getDoubles(int c, double [] vals, int from, int to){
    return getDoubles(c,vals,from,to,Double.NaN);
  }
  public double[] getDoubles(int c, double [] vals, int from, int to, double NA){
    if(_ids == null) return _cs[c].getDoubles(vals,from,to,NA);
    int x = Arrays.binarySearch(_ids,c);
    if(x >= 0) return _cs[x].getDoubles(vals,from,to,NA);
    for(int i = from; i < to; i++)
      vals[i] = 0;
    return vals;
  }

  public double min(int c){
    if(_ids == null) return _cs[c].min();
    int id = Arrays.binarySearch(_ids,c);
    if(id < 0) return 0;
    return _cs[id].min();
  }
  public double min(){return min(0);}
  public double max(int c){
    if(_ids == null) return _cs[c].max();
    if(_ids == null) return _cs[c].min();
    int id = Arrays.binarySearch(_ids,c);
    if(id < 0) return 0;
    return _cs[id].max();
  }
  public double max(){return max(0);}

  public long start(){return _start;}

  public void close(){close(new Futures()).blockForPending();}

  public Futures close(Futures fs){
    if(_modified_cols == null)return fs;
    Vec[] vecs = _vec.vecs();
    int lb = 0, ub = _vec._blockOffset[1];
    int vecId = 0;
    Key k = vecs[0].chunkKey(_cidx);
    DBlock db = DKV.getGet(k);
    for(int i = 0; i < _modified_cols.length; i++) {
      if(!_modified_cols[i])continue;
      int j = _vec.colFilter(i+_minModifiedCol);
      if((j >= ub || j < lb)){
        if(vecId != -1)DKV.put(k,db);
        while(j >= ub){
          vecId++;
          lb = ub;
          ub = _vec._blockOffset[vecId+1];
        }
        if(j < lb){
          if(vecId != -1) DKV.put(k,db);
          vecId = _vec.getBlockId(j);
          lb = _vec._blockOffset[vecId];
          ub = _vec._blockOffset[vecId+1];
        }
        k = vecs[vecId].chunkKey(_cidx);
        db = DKV.getGet(k);
      }
      int id = i + _minModifiedCol;
      if(_ids != null) id = Arrays.binarySearch(_ids,id);
      Chunk chk = _cs[id].compress();
      db = db.setChunk(j-lb,chk);
      assert chk._len == _len:_len + " != " + chk._len;
    }
    if(vecId != -1) DKV.put(k,db);
    return fs;
  }
  private boolean isSparse(){return _ids != null;}
  public Chunk getChunk(int c){
    int id = c;
    if(_ids != null)id = Arrays.binarySearch(_ids,c);
    if(id < 0) return new C0DChunk(0,_len);
    return _cs[id];
  }
  public void setChunk(int c, double [] ds){
    setChunk(c,(C)new NewChunk(ds).compress());
  }
  public void setChunk(int c, Chunk chk){
    if(_ids == null) {
      _cs[c] = (C)chk;
      return;
    }
    int id = Arrays.binarySearch(_ids,c);
    if(id < 0) {
      Chunk [] denseChks = new Chunk[_numCols];
      int j = 0;
      for(int i = 0; i < denseChks.length; ++i){
        if(j < _ids.length && i == _ids[j]){
          denseChks[i] = _cs[j++];
        } else denseChks[i] = new C0DChunk(0,_len);
      }
      denseChks[c] = chk;
      _cs = (C[])denseChks;
      _ids = null;
    } else _cs[id] = (C)chk;
  }
  public NewChunk getChunkInflated(int c){
    Chunk chk = getChunk(c);
    if(!(chk instanceof NewChunk))
      setChunk(c,chk.inflate_impl(new NewChunk(_vec.getType(c))));
    if(_cs.length == 0)
      System.out.println("haha");
    return (NewChunk) _cs[c];
  }


  public Chunk[] getChunks(){return _cs;}


  ModifiedBlocks _modified;



  private class ModifiedBlocks {
    int [] _modified_cols_start = new int[1];
    int [] _modified_cols_end = new int[1];
    int  _pos;
    int _lastId;
    ArrayUtils.IntAry _modifiedBlocks = new ArrayUtils.IntAry();
    int size() {return _modifiedBlocks.size();}
    public int [] modifiedBlocks(){return _modifiedBlocks.toArray();}

    private boolean written_to(int i) {
      if (i >= _modified_cols_start[_lastId] && i < _modified_cols_end[_lastId])
        return true;
      int k = Arrays.binarySearch(_modified_cols_start, i);
      if (k >= 0) return true;
      k = -k - 2;
      boolean res = (k >= 0 && i < _modified_cols_end[k]);
      if (res) _lastId = k;
      return res;
    }

    private void setWrite(int i){
      if (!written_to(i)) {
        int blockId = _vec.getBlockId(i);
        _vec.vecs()[blockId].preWriting();
        _modifiedBlocks.add(blockId);
        int from = _vec._blockOffset[blockId];
        int to = _vec._blockOffset[blockId+1];
        if(_modified_cols_start == null) {
          _modified_cols_start = new int[]{from};
          _modified_cols_end = new int[]{to};
        } else {
          int k = - Arrays.binarySearch(_modified_cols_start,i)-1;
          if(k < _modified_cols_start.length && _modified_cols_start[k] == to)
            _modified_cols_start[k] = from;
          else if(k > 0 && _modified_cols_end[k-1] == from){
            _modified_cols_end[k-1] = to;
          } else {
            if(_modified_cols_start.length == _pos){
              int newlen = Math.min(_modified_cols_start.length*2,_numCols);
              _modified_cols_start = Arrays.copyOf(_modified_cols_start,newlen);
              _modified_cols_end = Arrays.copyOf(_modified_cols_end,newlen);
            }
            for(int l = _modified_cols_start.length-1; l > k; --l){
              _modified_cols_start[l] = _modified_cols_start[l-1];
              _modified_cols_end[l] = _modified_cols_end[l-1];
            }
          }
        }
      }
    }
  }


  boolean [] _modified_cols;
  int _minModifiedCol = Integer.MAX_VALUE;

  private boolean setModified(int col){
    if(_modified_cols == null) {
      int x = col < 16?0:col;
      _modified_cols = new boolean[Math.min(16,_numCols-x)];
      _modified_cols[col - x] = true;
      _minModifiedCol = x;
      assert (_modified_cols.length + _minModifiedCol) <= _numCols;
      return true;
    }
    if(col < _minModifiedCol){
      int diff;
      if(col <= 16){
        diff = _minModifiedCol;
        _minModifiedCol = 0;
      } else {
        int x = (col >> 1);
        diff = _minModifiedCol - x;
        col -= x;
        _minModifiedCol = x;
      }
      boolean [] modified_cols = new boolean[_modified_cols.length+diff];
      System.arraycopy(_modified_cols,0,modified_cols,diff,_modified_cols.length);
      _modified_cols = modified_cols;
      _modified_cols[col] = true;
      assert (_modified_cols.length + _minModifiedCol) <= _numCols:" newlen = " + (_modified_cols.length +  _minModifiedCol) + ", maxlen = " + _numCols + ", diff = " + diff;
      return true;
    } else {
      col -= _minModifiedCol;
      if(_modified_cols.length <= col)
        _modified_cols = Arrays.copyOf(_modified_cols,Math.min(_numCols-_minModifiedCol,Math.max(col+4,2*_modified_cols.length)));
      if(_modified_cols[col])return false;
      _modified_cols[col] = true;
      return false;
    }
  }

  private void setWrite(int col){
    if(!setModified(col))return;
    if(_modified == null) _modified = new ModifiedBlocks();
    _modified.setWrite(_vec.colFilter(col));
  }

  public final double set(int i, double d){ set(i,0,d); return d; }

  public final void setAll(int c, double val){
    _cs[c] = (C)new C0DChunk(val,_len);
  }
  public final void set(int i, C ck){
    setWrite(i);
    _cs[i] = ck;
  }
  public final double set(int i, int j, double d){
    setWrite(j);
    if(!_cs[j].set_impl(i,d)){
      _cs[j] = (C)_cs[j].inflate_impl(new NewChunk(_vec.getType(j)));
      _cs[j].set_impl(i,d);
    }
    return d;
  }

  public long set(int i, int j, long l){
    setWrite(j);
    int id = j;
    if(_ids != null)
      id = Arrays.binarySearch(_ids,j);
    if( id < 0)
      getChunkInflated(j).set_impl(i,l);
    else if(!_cs[id].set_impl(i,l)){
      _cs[id] = (C)_cs[id].inflate_impl(new NewChunk(_vec.getType(j)));
      _cs[id].set_impl(i,l);
      assert _cs[id].len() == _len;
    }
    return l;
  }

  public boolean hasNA(){return hasNA(0);}
  public boolean hasNA(int c){
    if(_ids != null){
      c = Arrays.binarySearch(_ids,c);
      if(c < 0) return false;
    }
    return _cs[c].hasNA();
  }

  public final double atd(int i){ return atd(i,0);}
  public final double atd(int i, int j){
    if(_ids != null){
      j = Arrays.binarySearch(_ids,j);
      if(j < 0) return 0;
    }
    return _cs[j].atd(i);
  }

  public int chunkRelativeOffset(long globalRowId){
    long start = start();
    long x = globalRowId - (start>0 ? start : 0);
    if( 0 <= x && x < _len) return(int)x;
    throw new ArrayIndexOutOfBoundsException(""+start+" <= "+globalRowId+" < "+(start+ _len));
  }

  public final long at16l(int i){ return at16l(i,0);}
  public final long at16l(int i, int j){
    if(j < 0 || j > _numCols)
      throw new ArrayIndexOutOfBoundsException(j);
    if(_ids != null){
      j = Arrays.binarySearch(_ids,j);
      if(j < 0) throw new IllegalArgumentException("not a UUId chunk"); // UUID chunks should not be 0-sparse
    }
    return _cs[j].at16l(i);
  }
  public final long at16h(int i){ return at16h(i,0);}
  public final long at16h(int i, int j){
    if(j < 0 || j > _numCols)
      throw new ArrayIndexOutOfBoundsException(j);
    if(_ids != null){
      j = Arrays.binarySearch(_ids,j);
      if(j < 0) throw new IllegalArgumentException("not a UUId chunk"); // UUID chunks should not be 0-sparse
    }
    return _cs[j].at16h(i);
  }


  public boolean isNA(int i){ return isNA(i,0);}
  public boolean isNA(int i, int j){
    if(_ids != null){
      j = Arrays.binarySearch(_ids,j);
      if(j < 0) return false;
    }
    return _cs[j].isNA(i);
  }

  public long at8(int i){ return at8(i,0);}
  public long at8(int i, int j){
    if(_ids != null){
      j = Arrays.binarySearch(_ids,j);
      if(j < 0) return 0;
    }
    return _cs[j].at8(i);
  }

  public int at4(int i){ return at4(i,0);}
  public int at4(int i, int j){
    if(_ids != null){
      j = Arrays.binarySearch(_ids,j);
      if(j < 0) return 0;
    }
    return _cs[j].at4(i);
  }

  public BufferedString atStr(BufferedString str,int i){
    return atStr(str,i,0);
  }
  public BufferedString atStr(BufferedString str,int i, int j){
    if(_ids != null){
      j = Arrays.binarySearch(_ids,j);
      if(j < 0) throw new ArrayIndexOutOfBoundsException(j);
    }
    return _cs[j].atStr(str,i);
  }

  public String set(int i, int j, String str){
    if(_ids != null){
      j = Arrays.binarySearch(_ids,j);
      if(j < 0) throw new ArrayIndexOutOfBoundsException(j);
    }
    if(!_cs[j].set_impl(i,str))
      (_cs[j] = (C)_cs[j].inflate_impl(new NewChunk(Vec.T_STR))).set_impl(i,str);
    return str;
  }
  public BufferedString set(int i, int j, BufferedString str){
    if(_ids != null){
      j = Arrays.binarySearch(_ids,j);
      if(j < 0) throw new ArrayIndexOutOfBoundsException(j);
    }
    if(!_cs[j].set_impl(i,str))
      (_cs[j] = (C)_cs[j].inflate_impl(new NewChunk(Vec.T_STR))).set_impl(i,str);
    return str;
  }

  private Chunk getOrMakeSparseChunk(int j){
    int j2 = Arrays.binarySearch(_ids,j);
    if(j2 > j) return _cs[j2];
    j2 = -j2 - 1;
    int n = _cs.length;
    _cs = Arrays.copyOf(_cs,n+1);
    _ids = Arrays.copyOf(_ids,n+1);
    for(int i = n; i > j2; --i) {
      _cs[i + 1] = _cs[i];
      _ids[i+1] = _ids[i+1];
    }
    _ids[j2] = j;
    return _cs[j2] = (C)new C0DChunk(0, _len);
  }

  public void setNA(int i, int j){
    Chunk c = _ids != null?getOrMakeSparseChunk(j):_cs[j];
    c.setNA_impl(i);
  }

  public boolean hasFloat(int c) {
    if(_ids != null){
      int j = Arrays.binarySearch(_ids,c);
      return j >= 0 && _cs[j].hasFloat();
    }
    return _cs[c].hasFloat();
  }
  public boolean hasFloat() {
    return hasFloat(0);
  }


  public int sparseLenZero(int c) {
    if(_ids != null){
      int j = Arrays.binarySearch(_ids,c);
      return j >= 0?_cs[j].sparseLenZero():0;
    }
    return _cs[c].sparseLenZero();
  }
  public int sparseLenZero(){return sparseLenZero(0);}

  public int sparseLenNA(int c) {
    if(_ids != null){
      int j = Arrays.binarySearch(_ids,c);
      return j >= 0?_cs[j].sparseLenNA():0;
    }
    return _cs[c].sparseLenZero();
  }
  public int sparseLenNA(){return sparseLenNA(0);}


  public boolean isSparseNA(int c){
    if(_ids != null){
      int j = Arrays.binarySearch(_ids,c);
      return j >= 0?_cs[j].isSparseNA():false;
    }
    return _cs[c].isSparseZero();
  }
  public boolean isSparseNA(){return isSparseNA(0);}
  public boolean isSparseZero(int c){
    if(_ids != null){
      int j = Arrays.binarySearch(_ids,c);
      return j >= 0?_cs[j].isSparseZero():true;
    }
    return _cs[c].isSparseZero();
  }
  public boolean isSparseZero(){return isSparseZero(0);}

  public int nextNZ(int i) { return nextNZ(i,0);}
  public int nextNZ(int i, int j) {
    if(_ids != null){
      j = Arrays.binarySearch(_ids,j);
      if(j < 0) return _len;
    }
    return _cs[j].nextNZ(i);
  }

  public void add2Chunk(int srcCol, NewChunk nc, int from, int to) {
    _cs[srcCol].add2Chunk(nc,from,to);
  }

  public boolean isString(int c) {return _cs[c] instanceof CStrChunk;}
  public boolean isUUID(int c) {return _cs[c] instanceof C16Chunk;}
  public byte precision(int c){ return getChunk(c).precision();}
  public byte precision(){ return precision(0);}

  public DVal getInflated(int i, int j, DVal v){
    return _cs[j].getInflated(i,v);
  }

  public void setInflated(int i, int j, DVal v){
    if(!_cs[j].setInflated(i,v)){
      _cs[j] = (C)_cs[j].inflate_impl(new NewChunk(_vec.getType(j)));
      _cs[j].setInflated(i,v);
    }
  }

  public void add2Chunk(int c, NewChunkAry nchks, int c1, int[] ids) {
    getChunk(c).add2Chunk(nchks.getChunkInflated(c1),ids);
  }

  public void add2Chunk(int i, ChunkAry oc, int i1, int from, int to) {
    getChunk(i).add2Chunk(oc.getChunkInflated(i1),from,to);
  }

  public int cidx() {return _cidx;}


  public DBlock copyIntoNewBlock() {
    if(_cs.length == 1)
      return _cs[0].deepCopy();
    Chunk [] cs = _cs.clone();
    for(int i =0 ; i < cs.length; ++i)
      cs[i] = cs[i].deepCopy();
    if(_ids != null) // sparse
      throw H2O.unimpl();
    return new DBlock.MultiChunkBlock(cs);
  }
}
