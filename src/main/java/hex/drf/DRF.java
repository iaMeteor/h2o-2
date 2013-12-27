package hex.drf;

import static water.util.Utils.*;
import hex.ShuffleTask;
import hex.gbm.*;
import hex.gbm.DTree.DecidedNode;
import hex.gbm.DTree.LeafNode;
import hex.gbm.DTree.TreeModel.TreeStats;
import hex.gbm.DTree.UndecidedNode;

import java.util.Arrays;
import java.util.Random;

import jsr166y.ForkJoinTask;
import water.*;
import water.H2O.H2OCountedCompleter;
import water.api.DRFProgressPage;
import water.api.DocGen;
import water.api.Request.API;
import water.fvec.*;
import water.util.*;
import water.util.Log.Tag.Sys;

// Random Forest Trees
public class DRF extends SharedTreeModelBuilder<DRF.DRFModel> {
  static final int API_WEAVER = 1; // This file has auto-gen'd doc & json fields
  static public DocGen.FieldDoc[] DOC_FIELDS; // Initialized from Auto-Gen code.

  @API(help = "Columns to randomly select at each level, or -1 for sqrt(#cols)", filter = Default.class, lmin=-1, lmax=100000)
  int mtries = -1;

  @API(help = "Sample rate, from 0. to 1.0", filter = Default.class, dmin=0, dmax=1)
  float sample_rate = 0.6666667f;

  @API(help = "Seed for the random number generator", filter = Default.class)
  long seed = 0x1321e74a0192470cL; // Only one hardcoded seed to receive the same results between runs

  @API(help = "Compute variable importance (true/false).", filter = Default.class )
  boolean importance = false; // compute variable importance

  @API(help="Use a (lot) more memory in exchange for speed when running distributed.", filter=myClassFilter.class)
  public boolean build_tree_per_node = false;
  class myClassFilter extends DRFCopyDataBoolean { myClassFilter() { super("source"); } }

  @API(help = "Computed number of split features")
  protected int _mtry;

  /** DRF model holding serialized tree and implementing logic for scoring a row */
  public static class DRFModel extends DTree.TreeModel {
    static final int API_WEAVER = 1; // This file has auto-gen'd doc & json fields
    static public DocGen.FieldDoc[] DOC_FIELDS; // Initialized from Auto-Gen code.
    final int _mtries;
    final float _sample_rate;
    final long _seed;
    public DRFModel(Key key, Key dataKey, Key testKey, String names[], String domains[][], int ntrees, int max_depth, int min_rows, int nbins, int mtries, float sample_rate, long seed) {
      super(key,dataKey,testKey,names,domains,ntrees, max_depth, min_rows, nbins);
      _mtries = mtries;
      _sample_rate = sample_rate;
      _seed = seed;
    }
    public DRFModel(DRFModel prior, DTree[] trees, double err, long [][] cm, TreeStats tstats) {
      super(prior, trees, err, cm, tstats);
      _mtries = prior._mtries;
      _sample_rate = prior._sample_rate;
      _seed = prior._seed;
    }
    @Override protected float[] score0(double data[], float preds[]) {
      float[] p = super.score0(data, preds);
      int ntrees = numTrees();
      if (p.length==1) { if (ntrees>0) div(p, ntrees); } // regression - compute avg over all trees
      else { // classification
        float s = sum(p);
        if (s>0) div(p, s); // unify over all classes
      }
      return p;
    }
    @Override protected void generateModelDescription(StringBuilder sb) {
      DocGen.HTML.paragraph(sb,"mtries: "+_mtries+", Sample rate: "+_sample_rate+", Seed: "+_seed);
    }
    @Override protected void toJavaUnifyPreds(SB bodySb) {
      if (isClassifier()) {
        bodySb.i().p("float sum = 0;").nl();
        bodySb.i().p("for(int i=1; i<preds.length; i++) sum += preds[i];").nl();
        bodySb.i().p("for(int i=1; i<preds.length; i++) preds[i] = (float) preds[i] / sum;").nl();
      } else bodySb.i().p("preds[1] = preds[1]/NTREES;").nl();
    }
  }
  public Frame score( Frame fr ) { return ((DRFModel)UKV.get(dest())).score(fr);  }

  @Override protected Log.Tag.Sys logTag() { return Sys.DRF__; }
  @Override protected DRFModel makeModel( DRFModel model, DTree ktrees[], double err, long cm[][], TreeStats tstats) {
    return new DRFModel(model, ktrees, err, cm, tstats);
  }
  public DRF() { description = "Distributed RF"; ntrees = 50; max_depth = 999; min_rows = 1; }

  /** Return the query link to this page */
  public static String link(Key k, String content) {
    RString rs = new RString("<a href='DRF.query?source=%$key'>%content</a>");
    rs.replace("key", k.toString());
    rs.replace("content", content);
    return rs.toString();
  }

  // ==========================================================================

  // Compute a DRF tree.

  // Start by splitting all the data according to some criteria (minimize
  // variance at the leaves).  Record on each row which split it goes to, and
  // assign a split number to it (for next pass).  On *this* pass, use the
  // split-number to build a per-split histogram, with a per-histogram-bucket
  // variance.
  @Override protected void logStart() {
    Log.info("Starting DRF model build...");
    super.logStart();
    Log.info("    mtry: " + mtries);
    Log.info("    sample_rate: " + sample_rate);
    Log.info("    seed: " + seed);
  }

  @Override protected Status exec() {
    logStart();
    buildModel();
    return Status.Done;
  }

  @Override protected Response redirect() {
    return DRFProgressPage.redirect(this, self(), dest());
  }

  @Override protected void init() {
    super.init();
    // Initialize local variables
    _mtry = (mtries==-1) ? // classification: mtry=sqrt(_ncols), regression: mtry=_ncols/3
        ( classification ? Math.max((int)Math.sqrt(_ncols),1) : Math.max(_ncols/3,1))  : mtries;
    if (!(1 <= _mtry && _mtry <= _ncols)) throw new IllegalArgumentException("Computed mtry should be in interval <1,#cols> but it is " + _mtry);
    if (!(0.0 < sample_rate && sample_rate <= 1.0)) throw new IllegalArgumentException("Sample rate should be interval (0,1> but it is " + sample_rate);
  }

  @Override protected void buildModel( final Frame fr, String names[], String domains[][], final Key outputKey, final Key dataKey, final Key testKey, final Timer t_build ) {

    DRFModel model = new DRFModel(outputKey,dataKey,validation==null?null:testKey,names,domains,ntrees, max_depth, min_rows, nbins, mtries, sample_rate, seed);
    DKV.put(outputKey, model);

    // The RNG used to pick split columns
    Random rand = createRNG(seed);

    // Prepare working columns
    new SetWrkTask().doAll(fr);

    int tid = 0;
    DTree[] ktrees = null;
    // Prepare tree statistics
    TreeStats tstats = new TreeStats();
    // Build trees until we hit the limit
    for( tid=0; tid<ntrees; tid++) {
      // At each iteration build K trees (K = nclass = response column domain size)

      // TODO: parallelize more? build more than k trees at each time, we need to care about temporary data
      // Idea: launch more DRF at once.
      ktrees = buildNextKTrees(fr,_mtry,sample_rate,rand);
      if( cancelled() ) break; // If canceled during building, do not bulkscore

      // Check latest predictions
      tstats.updateBy(ktrees);
      model = doScoring(model, outputKey, fr, ktrees, tid, tstats, false, validation==null, build_tree_per_node);
    }
    // Final scoring
    model = doScoring(model, outputKey, fr, ktrees, tid, tstats, true, validation==null, build_tree_per_node);
    // Compute variable importance if required
    if (classification && importance) {
      float varimp[] = doVarImp(model, fr);
      Log.info(Sys.DRF__,"Var. importance: "+Arrays.toString(varimp));
      // Update the model
      model.varimp = varimp;
      DKV.put(outputKey, model);
    }

    cleanUp(fr,t_build); // Shared cleanup
  }

  /* From http://www.stat.berkeley.edu/~breiman/RandomForests/cc_home.htm#varimp
   * In every tree grown in the forest, put down the oob cases and count the number of votes cast for the correct class.
   * Now randomly permute the values of variable m in the oob cases and put these cases down the tree.
   * Subtract the number of votes for the correct class in the variable-m-permuted oob data from the number of votes
   * for the correct class in the untouched oob data.
   * The average of this number over all trees in the forest is the raw importance score for variable m.
   * */
  private float[] doVarImp(final DRFModel model, final Frame f) {
    // Score a dataset as usual but collects properties per tree.
    TreeVotes cx = TreeVotes.varimp(model, f, sample_rate);
    final double[] origAcc = cx.accuracy(); // original accuracy per tree
    final int ntrees = model.numTrees();
    final float[] varimp = new float[_ncols]; // output variable importance
    assert origAcc.length == ntrees; // make sure that numbers of trees correspond
    // For each variable launch one FJ-task to compute variable importance.
    H2OCountedCompleter[] computers = new H2OCountedCompleter[_ncols];
    for (int var=0; var<_ncols; var++) {
      final int variable = var;
      // WARNING: The code is shuffling all rows not only OOB rows.
      // Hence, after shuffling an OOB row can contain in shuffled column value from non-OOB row
      // The question is if it affects significatly var imp
      computers[var] = new H2OCountedCompleter() {
        @Override public void compute2() {
          Frame wf = new Frame(f); // create a copy of frame
          Vec varv = wf.vecs()[variable]; // vector which we use to measure variable importance
          Vec sv = ShuffleTask.shuffle(varv); // create a shuffled vector
          wf.replace(variable, sv); // replace a vector with shuffled vector
          // Compute oobee with shuffled data
          TreeVotes cd = TreeVotes.varimp(model, wf, sample_rate);
          double[] accdiff = cd.accuracy();
          assert accdiff.length == origAcc.length;
          // compute decrease of accuracy
          for (int t=0; t<ntrees;t++ ) {
            accdiff[t] = origAcc[t] - accdiff[t];
          }
          varimp[variable] = (float) avg(accdiff);
          // Remove shuffled vector
          UKV.remove(sv._key);
          tryComplete();
        }
      };
    }
    ForkJoinTask.invokeAll(computers);
    // after all varimp contains variable importance of all columns used by a model.
    return varimp;
  }

  /** Fill work columns:
   *   - classification: set 1 in the corresponding wrk col according to row response
   *   - regression:     copy response into work column (there is only 1 work column) */

  private class SetWrkTask extends MRTask2<SetWrkTask> {
    @Override public void map( Chunk chks[] ) {
      Chunk cy = chk_resp(chks);
      for( int i=0; i<cy._len; i++ ) {
        if( cy.isNA0(i) ) continue;
        if (classification) {
          int cls = (int)cy.at80(i);
          chk_work(chks,cls).set0(i,1L);
        } else {
          float pred = (float) cy.at0(i);
          chk_work(chks,0).set0(i,pred);
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // Build the next random k-trees
  private DTree[] buildNextKTrees(Frame fr, int mtrys, float sample_rate, Random rand) {
    // We're going to build K (nclass) trees - each focused on correcting
    // errors for a single class.
    final DTree[] ktrees = new DTree[_nclass];

    // Initial set of histograms.  All trees; one leaf per tree (the root
    // leaf); all columns
    DHistogram hcs[][][] = new DHistogram[_nclass][1/*just root leaf*/][_ncols];

    // Use for all k-trees the same seed. NOTE: this is only to make a fair
    // view for all k-trees
    long rseed = rand.nextLong();
    // Initially setup as-if an empty-split had just happened
    for( int k=0; k<_nclass; k++ ) {
      assert (_distribution!=null && classification) || (_distribution==null && !classification);
      if( _distribution == null || _distribution[k] != 0 ) { // Ignore missing classes
        // The Boolean Optimization
        // This optimization assumes the 2nd tree of a 2-class system is the
        // inverse of the first.  This is false for DRF (and true for GBM) -
        // DRF picks a random different set of columns for the 2nd tree.  
        //if( DTree.CRUNK && k==1 && _nclass==2 ) continue;
        ktrees[k] = new DRFTree(fr,_ncols,(char)nbins,(char)_nclass,min_rows,mtrys,rseed);
        boolean isBinom = classification;
        new DRFUndecidedNode(ktrees[k],-1, DHistogram.initialHist(fr,_ncols,nbins,hcs[k][0],isBinom) ); // The "root" node
      }
    }

    // Sample - mark the lines by putting 'OUT_OF_BAG' into nid(<klass>) vector
    Sample ss[] = new Sample[_nclass];
    for( int k=0; k<_nclass; k++)
      if (ktrees[k] != null) ss[k] = new Sample((DRFTree)ktrees[k], sample_rate).dfork(0,new Frame(vec_nids(fr,k)), build_tree_per_node);
    for( int k=0; k<_nclass; k++)
      if( ss[k] != null ) ss[k].getResult();

    int[] leafs = new int[_nclass]; // Define a "working set" of leaf splits, from leafs[i] to tree._len for each tree i

    // ----
    // One Big Loop till the ktrees are of proper depth.
    // Adds a layer to the trees each pass.
    int depth=0;
    for( ; depth<max_depth; depth++ ) {
      if( cancelled() ) return null;

      hcs = buildLayer(fr, ktrees, leafs, hcs, build_tree_per_node);

      // If we did not make any new splits, then the tree is split-to-death
      if( hcs == null ) break;
    }

    // Each tree bottomed-out in a DecidedNode; go 1 more level and insert
    // LeafNodes to hold predictions.
    for( int k=0; k<_nclass; k++ ) {
      DTree tree = ktrees[k];
      if( tree == null ) continue;
      int leaf = leafs[k] = tree.len();
      for( int nid=0; nid<leaf; nid++ ) {
        if( tree.node(nid) instanceof DecidedNode ) {
          DecidedNode dn = tree.decided(nid);
          for( int i=0; i<dn._nids.length; i++ ) {
            int cnid = dn._nids[i];
            if( cnid == -1 || // Bottomed out (predictors or responses known constant)
                tree.node(cnid) instanceof UndecidedNode || // Or chopped off for depth
                (tree.node(cnid) instanceof DecidedNode &&  // Or not possible to split
                 ((DecidedNode)tree.node(cnid))._split.col()==-1) ) {
              LeafNode ln = new DRFLeafNode(tree,nid);
              ln._pred = dn.pred(i);  // Set prediction into the leaf
              dn._nids[i] = ln.nid(); // Mark a leaf here
            }
          }
          // Handle the trivial non-splitting tree
          if( nid==0 && dn._split.col() == -1 )
            new DRFLeafNode(tree,-1,0);
        }
      }
    } // -- k-trees are done

    // ----
    // Move rows into the final leaf rows
    CollectPreds gp = new CollectPreds(ktrees,leafs).doAll(fr,build_tree_per_node);

    // Collect leaves stats
    for (int i=0; i<ktrees.length; i++) 
      if( ktrees[i] != null ) 
        ktrees[i].leaves = ktrees[i].len() - leafs[i];
    // DEBUG: Print the generated K trees
    // printGenerateTrees(ktrees);

    return ktrees;
  }

  // Read the 'tree' columns, do model-specific math and put the results in the
  // ds[] array, and return the sum.  Dividing any ds[] element by the sum
  // turns the results into a probability distribution.
  @Override protected double score0( Chunk chks[], double ds[/*nclass*/], int row ) {
    double sum=0;
    for( int k=0; k<_nclass; k++ ) // Sum across of likelyhoods
      sum+=(ds[k]=chk_tree(chks,k).at0(row));
    return sum;
  }

  // Collect and write predictions into leafs.
  private class CollectPreds extends MRTask2<CollectPreds> {
    final DTree _trees[]; // Read-only, shared (except at the histograms in the Nodes)
    final int   _leafs[]; // Number of active leaves (per tree)
    CollectPreds(DTree trees[], int leafs[]) { _leafs=leafs; _trees=trees; }
    @Override public void map( Chunk[] chks ) {
      // For all tree/klasses
      for( int k=0; k<_nclass; k++ ) {
        final DTree tree = _trees[k];
        final int   leaf = _leafs[k];
        if( tree == null ) continue; // Empty class is ignored
        // If we have all constant responses, then we do not split even the
        // root and the residuals should be zero.
        if( tree.root() instanceof LeafNode ) continue;
        final Chunk nids = chk_nids(chks,k); // Node-ids  for this tree/class
        final Chunk ct   = chk_tree(chks,k);
        for( int row=0; row<nids._len; row++ ) { // For all rows
          int nid = (int)nids.at80(row);         // Get Node to decide from
          // This is out-of-bag row - but we would like to track on-the-fly prediction for the row
          if( isOOBRow(nid) ) { 
            nid = oob2Nid(nid); 
            if( tree.node(nid) instanceof UndecidedNode ) // If we bottomed out the tree
              nid = tree.node(nid).pid();                 // Then take parent's decision
            DecidedNode dn = tree.decided(nid);           // Must have a decision point
            if( dn._split.col() == -1 )     // Unable to decide?
              dn = tree.decided(nid = tree.node(nid).pid()); // Then take parent's decision
            int leafnid = dn.ns(chks,row); // Decide down to a leafnode
            // Setup Tree(i) - on the fly prediction of i-tree for row-th row
            ct.set0(row, (float)(ct.at0 (row) + ((LeafNode)tree.node(leafnid)).pred() ));
          }
          // reset help column
          nids.set0(row,0);
        }
      }
    }
  }

  // A standard DTree with a few more bits.  Support for sampling during
  // training, and replaying the sample later on the identical dataset to
  // e.g. compute OOBEE.
  static class DRFTree extends DTree {
    final int _mtrys;           // Number of columns to choose amongst in splits
    final long _seeds[];        // One seed for each chunk, for sampling
    final transient Random _rand; // RNG for split decisions & sampling
    DRFTree( Frame fr, int ncols, char nbins, char nclass, int min_rows, int mtrys, long seed ) {
      super(fr._names, ncols, nbins, nclass, min_rows, seed);
      _mtrys = mtrys;
      _rand = createRNG(seed);
      _seeds = new long[fr.vecs()[0].nChunks()];
      for( int i=0; i<_seeds.length; i++ )
        _seeds[i] = _rand.nextLong();
    }
    // Return a deterministic chunk-local RNG.  Can be kinda expensive.
    @Override public Random rngForChunk( int cidx ) {
      long seed = _seeds[cidx];
      return createRNG(seed);
    }
  }

  @Override protected DecidedNode makeDecided( UndecidedNode udn, DHistogram hs[] ) { 
    return new DRFDecidedNode(udn,hs);
  }

  // DRF DTree decision node: same as the normal DecidedNode, but specifies a
  // decision algorithm given complete histograms on all columns.
  // DRF algo: find the lowest error amongst a random mtry columns.
  static class DRFDecidedNode extends DecidedNode {
    DRFDecidedNode( UndecidedNode n, DHistogram hs[] ) { super(n,hs); }
    @Override public DRFUndecidedNode makeUndecidedNode( DHistogram hs[] ) {
      return new DRFUndecidedNode(_tree,_nid, hs);
    }

    // Find the column with the best split (lowest score).
    @Override public DTree.Split bestCol( UndecidedNode u, DHistogram hs[] ) {
      DTree.Split best = new DTree.Split(-1,-1,false,Double.MAX_VALUE,Double.MAX_VALUE,0L,0L,0,0);
      if( hs == null ) return best;
      for( int i=0; i<u._scoreCols.length; i++ ) {
        int col = u._scoreCols[i];
        DTree.Split s = hs[col].scoreMSE(col);
        if( s == null ) continue;
        if( s.se() < best.se() ) best = s;
        if( s.se() <= 0 ) break; // No point in looking further!
      }
      return best;
    }
  }

  // DRF DTree undecided node: same as the normal UndecidedNode, but specifies
  // a list of columns to score on now, and then decide over later.
  // DRF algo: pick a random mtry columns
  static class DRFUndecidedNode extends UndecidedNode {
    DRFUndecidedNode( DTree tree, int pid, DHistogram[] hs ) { super(tree,pid, hs); }

    // Randomly select mtry columns to 'score' in following pass over the data.
    @Override public int[] scoreCols( DHistogram[] hs ) {
      DRFTree tree = (DRFTree)_tree;
      int[] cols = new int[hs.length];
      int len=0;
      // Gather all active columns to choose from.
      for( int i=0; i<hs.length; i++ ) {
        if( hs[i]==null ) continue; // Ignore not-tracked cols
        assert hs[i]._min < hs[i]._maxEx && hs[i].nbins() > 1 : "broken histo range "+hs[i];
        cols[len++] = i;        // Gather active column
      }
      int choices = len;        // Number of columns I can choose from
      assert choices > 0;

      // Draw up to mtry columns at random without replacement.
      for( int i=0; i<tree._mtrys; i++ ) {
        if( len == 0 ) break;   // Out of choices!
        int idx2 = tree._rand.nextInt(len);
        int col = cols[idx2];     // The chosen column
        cols[idx2] = cols[--len]; // Compress out of array; do not choose again
        cols[len] = col;          // Swap chosen in just after 'len'
      }
      assert choices - len > 0;
      return Arrays.copyOfRange(cols,len,choices);
    }
  }

  static class DRFLeafNode extends LeafNode {
    DRFLeafNode( DTree tree, int pid ) { super(tree,pid); }
    DRFLeafNode( DTree tree, int pid, int nid ) { super(tree,pid,nid); }
    // Insert just the predictions: a single byte/short if we are predicting a
    // single class, or else the full distribution.
    @Override protected AutoBuffer compress(AutoBuffer ab) { assert !Double.isNaN(pred()); return ab.put4f((float)pred()); }
    @Override protected int size() { return 4; }
  }

  // Deterministic sampling
  static class Sample extends MRTask2<Sample> {
    final DRFTree _tree;
    final float _rate;
    Sample( DRFTree tree, float rate ) { _tree = tree; _rate = rate; }
    @Override public void map( Chunk nids ) {
      Random rand = _tree.rngForChunk(nids.cidx());
      for( int row=0; row<nids._len; row++ )
        if( rand.nextFloat() >= _rate )
          nids.set0(row, OUT_OF_BAG);     // Flag row as being ignored by sampling
    }
  }
}
