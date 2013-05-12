package com.github.jsonldjava.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.jsonldjava.impl.NQuadTripleCallback;
import com.github.jsonldjava.utils.JSONUtils;

import static com.github.jsonldjava.core.JSONLDUtils.*;
import static com.github.jsonldjava.core.ToRDFUtils.*;

public class JSONLD {
	
	/**
	 * Performs JSON-LD compaction.
	 *
	 * @param input the JSON-LD input to compact.
	 * @param ctx the context to compact with.
	 * @param [options] options to use:
	 *          [base] the base IRI to use.
	 *          [strict] use strict mode (default: true).
	 *          [compactArrays] true to compact arrays to single values when
	 *            appropriate, false not to (default: true).
	 *          [graph] true to always output a top-level graph (default: false).
	 *          [skipExpansion] true to assume the input is expanded and skip
	 *            expansion, false not to, defaults to false.
	 *          [loadContext(url, callback(err, url, result))] the context loader.
	 * @param callback(err, compacted, ctx) called once the operation completes.
	 */
    public static Object compact(Object input, Object ctx, Options opts) throws JSONLDProcessingError {
    	// nothing to compact
        if (input == null) {
            return null;
        }
        
        // NOTE: javascript does this check before input check
        if (ctx == null) {
        	throw new JSONLDProcessingError("The compaction context must not be null.")
    		.setType(JSONLDProcessingError.Error.COMPACT_ERROR);
        }
        
        // set default options
        if (opts.base == null) {
        	opts.base = "";
        }
        if (opts.strict == null) {
            opts.strict = true;
        }
        if (opts.compactArrays == null) {
        	opts.compactArrays = true;
        }
        if (opts.graph == null) {
            opts.graph = false;
        }
        if (opts.skipExpansion == null) {
            opts.skipExpansion = false;
        }
        //JSONLDProcessor p = new JSONLDProcessor(opts);

        // expand input then do compaction
        Object expanded;
        try {
        	if (opts.skipExpansion) {
        		expanded = input;
        	} else {
        		expanded = JSONLD.expand(input, opts);
        	}
        } catch (JSONLDProcessingError e) {
        	throw new JSONLDProcessingError("Could not expand input before compaction.")
        		.setType(JSONLDProcessingError.Error.COMPACT_ERROR)
        		.setDetail("cause", e);
        }

        // process context
        ActiveContext activeCtx = new ActiveContext(opts);
        try {
            activeCtx = JSONLD.processContext(activeCtx, ctx, opts);
        } catch (JSONLDProcessingError e) {
            throw new JSONLDProcessingError("Could not process context before compaction.")
            	.setType(JSONLDProcessingError.Error.COMPACT_ERROR)
        		.setDetail("cause", e);
        }

        // do compaction
        Object compacted = new JSONLDProcessor(opts).compact(activeCtx, null, expanded);

        // cleanup
        if (opts.compactArrays && !opts.graph && isArray(compacted)) {
        	// simplify to a single item
        	if (((List<Object>)compacted).size() == 1) {
        		compacted = ((List<Object>) compacted).get(0);
        	}
        	// simplify to an empty object
        	else if (((List<Object>)compacted).size() == 0) {
        		compacted = new HashMap<String, Object>();
        	}
        }
        // always use array if graph option is on
        else if (opts.graph && isObject(compacted)) {
            List<Object> tmp = new ArrayList<Object>();
            tmp.add(compacted);
            compacted = tmp;
        }

        // follow @context key
        if (isObject(ctx) && ((Map<String, Object>) ctx).containsKey("@context")) {
            ctx = ((Map<String, Object>) ctx).get("@context");
        }

        // build output context
        ctx = JSONLDUtils.clone(ctx);
        if (!isArray(ctx)) {
            List<Object> lctx = new ArrayList<Object>();
            lctx.add(ctx);
            ctx = lctx;
        }
        
        // remove empty contexts
        List<Object> tmp = (List<Object>) ctx;
        ctx = new ArrayList<Object>();
        for (Object i : tmp) {
            if (!isObject(i) || ((Map) i).size() > 0) {
                ((List<Object>) ctx).add(i);
            }
        }

        // remove array if only one context
        boolean hasContext = ((List) ctx).size() > 0;
        if (((List) ctx).size() == 1) {
            ctx = ((List) ctx).get(0);
        }

        // add context and/or @graph
        if (isArray(compacted)) {
            String kwgraph = compactIri(activeCtx, "@graph");
            Object graph = compacted;
            compacted = new HashMap<String, Object>();
            if (hasContext) {
                ((Map<String, Object>) compacted).put("@context", ctx);
            }
            ((Map<String, Object>) compacted).put(kwgraph, graph);
        } else if (isObject(compacted) && hasContext) {
        	// reorder keys so @context is first
            Map<String, Object> graph = (Map<String, Object>) compacted;
            compacted = new HashMap<String, Object>();
            ((Map) compacted).put("@context", ctx);
            for (String key : graph.keySet()) {
                ((Map<String, Object>) compacted).put(key, graph.get(key));
            }
        }

        // frame needs the value of the compaction result's activeCtx 
        opts.compactResultsActiveCtx = activeCtx;
        return compacted;
    }

    public static Object compact(Object input, Map<String, Object> ctx) throws JSONLDProcessingError {
        return compact(input, ctx, new Options("", true));
    }
	
    /**
     * Performs JSON-LD expansion.
     *
     * @param input the JSON-LD input to expand.
     * @param [options] the options to use:
     *          [base] the base IRI to use.
     *          [keepFreeFloatingNodes] true to keep free-floating nodes,
     *            false not to, defaults to false.
     * @return the expanded result as a list
     */
    public static List<Object> expand(Object input, Options opts) throws JSONLDProcessingError {
    	if (opts.base == null) {
    		opts.base = "";
    	}
    	
    	if (opts.keepFreeFloatingNodes == null) {
    		opts.keepFreeFloatingNodes = false;
    	}
    	
    	// resolve all @context URLs in the input
    	input = JSONLDUtils.clone(input);
    	JSONLDUtils.resolveContextUrls(input);
    	
    	// do expansion
    	JSONLDProcessor p = new JSONLDProcessor(opts);
        Object expanded = p.expand(new ActiveContext(opts), null, input, false);

        // optimize away @graph with no other properties
        if (isObject(expanded) && ((Map) expanded).containsKey("@graph") && ((Map) expanded).size() == 1) {
            expanded = ((Map<String, Object>) expanded).get("@graph");
        } else if (expanded == null) {
        	expanded = new ArrayList<Object>();
        }

        // normalize to an array
        if (!isArray(expanded)) {
            List<Object> tmp = new ArrayList<Object>();
            tmp.add(expanded);
            expanded = tmp;
        }
        return (List<Object>) expanded;
    }

    public static List<Object> expand(Object input) throws JSONLDProcessingError {
        return expand(input, new Options(""));
    }
    
    /**
     * Performs JSON-LD flattening.
     *
     * @param input the JSON-LD to flatten.
     * @param ctx the context to use to compact the flattened output, or null.
     * @param [options] the options to use:
     *          [base] the base IRI to use.
     *          [loadContext(url, callback(err, url, result))] the context loader.
     * @param callback(err, flattened) called once the operation completes.
     * @throws JSONLDProcessingError 
     */
    public static Object flatten(Object input, Object ctx, Options opts) throws JSONLDProcessingError {
    	// set default options
    	if (opts.base == null) {
    		opts.base = "";
    	}
    	
    	// expand input
    	List<Object> _input;
    	try {
    		_input = expand(input, opts);
    	} catch (JSONLDProcessingError e) {
    		throw new JSONLDProcessingError("Could not expand input before flattening.")
    			.setType(JSONLDProcessingError.Error.FLATTEN_ERROR)
    			.setDetail("cause", e);
    	}
    	
    	Object flattened = new JSONLDProcessor(opts).flatten(_input);
    	
    	if (ctx == null) {
    		return flattened;
    	}
    	
    	// compact result (force @graph option to true, skip expansion)
    	opts.graph = true;
    	opts.skipExpansion = true;
    	try {
    		Object compacted = compact(flattened, ctx, opts);
    		return compacted;
    	} catch (JSONLDProcessingError e) {
    		throw new JSONLDProcessingError("Could not compact flattened output.")
			.setType(JSONLDProcessingError.Error.FLATTEN_ERROR)
			.setDetail("cause", e);
    	}
    }
    
    public static Object flatten(Object input, Object ctxOrOptions) throws JSONLDProcessingError {
    	if (ctxOrOptions instanceof Options) {
    		return flatten(input, null, (Options)ctxOrOptions);
    	} else {
    		return flatten(input, ctxOrOptions, new Options(""));
    	}
    }

    public static Object flatten(Object input) throws JSONLDProcessingError {
    	return flatten(input, null, new Options(""));
    }
    
    /**
     * Performs JSON-LD framing.
     *
     * @param input the JSON-LD input to frame.
     * @param frame the JSON-LD frame to use.
     * @param [options] the framing options.
     *          [base] the base IRI to use.
     *          [embed] default @embed flag (default: true).
     *          [explicit] default @explicit flag (default: false).
     *          [omitDefault] default @omitDefault flag (default: false).
     *          [loadContext(url, callback(err, url, result))] the context loader.
     * @param callback(err, framed) called once the operation completes.
	 * @throws JSONLDProcessingError 
     */
    public static Object frame(Object input, Map<String,Object> frame, Options opts) throws JSONLDProcessingError {
    	// set default options
    	if (opts.base == null) {
    		opts.base = "";
    	}
    	if (opts.embed == null) {
    		opts.embed  = true;
    	}
    	if (opts.explicit == null) {
    		opts.explicit = false;
    	}
    	if (opts.omitDefault == null) {
    		opts.omitDefault = false;
    	}

    	// TODO: add sanity checks for input and throw JSONLDProcessingErrors when incorrect input is used
    	// preserve frame context
    	//ActiveContext ctx = frame.containsKey("@context") ? new ActiveContext((Map<String, Object>) frame.get("@context"), opts) : new ActiveContext(opts);
    	Map<String,Object> ctx = frame.containsKey("@context") ? (Map<String, Object>) frame.get("@context") : new HashMap<String,Object>();
    	
    	// expand input
    	Object expanded;
    	try {
    		expanded = JSONLD.expand(input, opts);
    	} catch (JSONLDProcessingError e) {
    		throw new JSONLDProcessingError("Could not expand input before framing.")
			.setType(JSONLDProcessingError.Error.FRAME_ERROR)
			.setDetail("cause", e);
    	}
    	// expand frame
    	Object expandedFrame;
    	Options opts2 = opts.clone();
		opts2.keepFreeFloatingNodes = true;
    	try {
    		expandedFrame = JSONLD.expand(frame, opts2);
		} catch (JSONLDProcessingError e) {
			throw new JSONLDProcessingError("Could not expand frame before framing.")
			.setType(JSONLDProcessingError.Error.FRAME_ERROR)
			.setDetail("cause", e);
		}

    	// do framing
    	Object framed = new JSONLDProcessor(opts).frame(expanded, expandedFrame);
    	// compact results (force @graph option to true, skip expansion)
    	opts2.graph = true;
    	opts2.skipExpansion = true;
    	try {
    		Object compacted = compact(framed, ctx, opts2);
    		// get resulting activeCtx
    		ActiveContext actx = opts2.compactResultsActiveCtx;
    		// get graph alias
    		String graph = compactIri(actx, "@graph");
    		((HashMap<String, Object>) compacted).put(graph, removePreserve(actx, ((Map<String,Object>) compacted).get(graph), opts2));
    		return compacted;
    	} catch (JSONLDProcessingError e) {
    		throw new JSONLDProcessingError("Could not compact framed output.")
			.setType(JSONLDProcessingError.Error.FRAME_ERROR)
			.setDetail("cause", e);
    	}
    }

	public static Object frame(Object input, Map<String, Object> frame) throws JSONLDProcessingError {
    	return frame(input, frame, new Options(""));
    }
    
    /**
     * Processes a local context, resolving any URLs as necessary, and returns a
     * new active context in its callback.
     *
     * @param activeCtx the current active context.
     * @param localCtx the local context to process.
     * @param [options] the options to use:
     *          [loadContext(url, callback(err, url, result))] the context loader.
     * @param callback(err, ctx) called once the operation completes.
     */
    private static ActiveContext processContext(ActiveContext activeCtx, Object localCtx, Options opts) throws JSONLDProcessingError {
    	// set default options
    	if (opts.base == null) {
    		opts.base = "";
    	}
    	
    	// return initial context early for null context
    	if (localCtx == null) {
    		return new ActiveContext(opts);
    	}
    	
    	// retrieve URLs in localCtx
    	localCtx = JSONLDUtils.clone(localCtx);
    	if (isString(localCtx) || (isObject(localCtx) && !((HashMap<String, Object>) localCtx).containsKey("@context"))) {
    		Map<String,Object> tmp = new HashMap<String, Object>();
    		tmp.put("@context", localCtx);
    		localCtx = tmp;
    	}
    	
    	resolveContextUrls(localCtx);
    	
    	return new JSONLDProcessor(opts).processContext(activeCtx, localCtx);
    }
    
    /**
     * Performs RDF dataset normalization on the given JSON-LD input. The output
     * is an RDF dataset unless the 'format' option is used.
     *
     * @param input the JSON-LD input to normalize.
     * @param [options] the options to use:
     *          [base] the base IRI to use.
     *          [format] the format if output is a string:
     *            'application/nquads' for N-Quads.
     *          [loadContext(url, callback(err, url, result))] the context loader.
     * @param callback(err, normalized) called once the operation completes.
     * @throws JSONLDProcessingError
     */
    public static Object normalize(Object input, Options options) throws JSONLDProcessingError {
    	if (options.base == null) {
    		options.base = "";
    	}
    	
    	Options opts = options.clone();
    	opts.format = null;
    	Object dataset;
    	try {
    		dataset = toRDF(input, opts);
    	} catch (JSONLDProcessingError e) {
    		throw new JSONLDProcessingError("Could not convert input to RDF dataset before normalization.")
    			.setType(JSONLDProcessingError.Error.NORMALIZE_ERROR)
    			.setDetail("cause", e);
    	}
    	return new JSONLDProcessor(opts).normalize(dataset);
    }
    
    public static Object normalize(Object input) throws JSONLDProcessingError {
    	return normalize(input, new Options(""));
    }
    
    /**
     * Outputs the RDF dataset found in the given JSON-LD object.
     *
     * @param input the JSON-LD input.
     * @param [options] the options to use:
     *          [base] the base IRI to use.
     *          [format] the format to use to output a string:
     *            'application/nquads' for N-Quads (default).
     *          [loadContext(url, callback(err, url, result))] the context loader.
     * @param callback(err, dataset) called once the operation completes.
     */
    public static Object toRDF(Object input, Options options) throws JSONLDProcessingError {
    	if (options.base == null) {
    		options.base = "";
    	}
    	
    	Object expanded;
    	try {
    		expanded = JSONLD.expand(input, options);
    	} catch (JSONLDProcessingError e) {
    		throw new JSONLDProcessingError("Could not expand input before conversion to RDF.")
    			.setType(JSONLDProcessingError.Error.RDF_ERROR)
    			.setDetail("cause", e);
    	}
    	
    	UniqueNamer namer = new UniqueNamer("_:b");
    	Map<String,Object> nodeMap = new HashMap<String, Object>() {{
    		put("@default", new HashMap<String, Object>());
    	}};
    	createNodeMap(expanded, nodeMap, "@default", namer);
    	
		// output RDF dataset
		Map<String,Object> dataset = new JSONLDProcessor(options).toRDF(nodeMap);
		if (options.format != null) {
			if ("application/nquads".equals(options.format)) {
				return toNQuads(dataset);
			} else {
				throw new JSONLDProcessingError("Unknown output format.")
					.setType(JSONLDProcessingError.Error.UNKNOWN_FORMAT)
					.setDetail("format", options.format);
			}
		}
		return dataset;    	
    }
    
    public static void toRDF(Object input) throws JSONLDProcessingError {
    	toRDF(input, new Options(""));
    }
    
    public static Object fromRDF(Object input, Options opts, JSONLDSerializer serializer) throws JSONLDProcessingError {
    	if (opts.useRdfType == null) {
    		opts.useRdfType = false;
    	}
    	if (opts.useNativeTypes == null) {
    		opts.useNativeTypes = true;
    	}
    	serializer.parse(input);
    	Object rval = new JSONLDProcessor(opts).fromRDF(serializer.getStatements());
    	rval = serializer.finalize(rval);
    	return rval;
    }
    
    public static Object fromRDF(Object input, JSONLDSerializer serializer) throws JSONLDProcessingError {
    	return fromRDF(input, new Options(""), serializer);
    }

	public static Object simplify(Object input, Options opts) throws JSONLDProcessingError {
		// TODO Auto-generated method stub
		if (opts.base == null) {
    		opts.base = "";
    	}
		return new JSONLDProcessor(opts).simplify(input);
	}
	
	public static Object simplify(Object input) throws JSONLDProcessingError {
		return simplify(input, new Options(""));
	}
}