/*******************************************************************************
 * Copyright (c) 2015 Bruno Medeiros and other Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Bruno Medeiros - initial API and implementation
 *******************************************************************************/
package melnorme.lang.ide.ui.text;

import static melnorme.utilbox.core.Assert.AssertNamespace.assertNotNull;
import static melnorme.utilbox.core.Assert.AssertNamespace.assertUnreachable;
import static melnorme.utilbox.core.CoreUtil.array;

import java.util.Map;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.AbstractInformationControlManager;
import org.eclipse.jface.text.DefaultInformationControl;
import org.eclipse.jface.text.IAutoEditStrategy;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.jface.text.contentassist.IContentAssistant;
import org.eclipse.jface.text.information.IInformationPresenter;
import org.eclipse.jface.text.information.IInformationProvider;
import org.eclipse.jface.text.information.InformationPresenter;
import org.eclipse.jface.text.reconciler.IReconciler;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationHover;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.texteditor.ITextEditor;

import _org.eclipse.jdt.internal.ui.text.HTMLAnnotationHover;
import melnorme.lang.ide.core.TextSettings_Actual.LangPartitionTypes;
import melnorme.lang.ide.core.text.ISourceBufferExt;
import melnorme.lang.ide.core.text.TextSourceUtils;
import melnorme.lang.ide.core.text.format.FormatterIndentMode;
import melnorme.lang.ide.ui.CodeFormatterConstants;
import melnorme.lang.ide.ui.EditorSettings_Actual;
import melnorme.lang.ide.ui.LangUIPlugin;
import melnorme.lang.ide.ui.LangUIPlugin_Actual;
import melnorme.lang.ide.ui.editor.LangSourceViewer;
import melnorme.lang.ide.ui.editor.ProjectionViewerExt;
import melnorme.lang.ide.ui.editor.hover.BestMatchHover;
import melnorme.lang.ide.ui.editor.hover.HoverInformationProvider;
import melnorme.lang.ide.ui.editor.structure.LangOutlineInformationControl.OutlineInformationControlCreator;
import melnorme.lang.ide.ui.editor.structure.StructureElementInformationProvider;
import melnorme.lang.ide.ui.text.completion.CompletionProposalsGrouping;
import melnorme.lang.ide.ui.text.completion.ContenAssistProcessorExt.NullContentAssistProcessorExt;
import melnorme.lang.ide.ui.text.completion.ContentAssistantExt;
import melnorme.lang.ide.ui.text.completion.LangContentAssistProcessor;
import melnorme.lang.ide.ui.text.completion.LangContentAssistProcessor.ContentAssistCategoriesBuilder;
import melnorme.utilbox.collections.Indexable;

public abstract class AbstractLangSourceViewerConfiguration extends LangBasicSourceViewerConfiguration {
	
	protected final ISourceBufferExt sourceBuffer;
	/** Editor for this configuration. Can be null. It is preferred that sourceBuffer be used instead of this, 
	 * but some legacy or third-party API requires the editor itself */
	protected final ITextEditor editor_opt; // can be null
	
	public AbstractLangSourceViewerConfiguration(IPreferenceStore preferenceStore, 
			ISourceBufferExt sourceBuffer, ITextEditor editor) {
		super(preferenceStore);
		this.sourceBuffer = assertNotNull(sourceBuffer);
		this.editor_opt = editor;
	}
	
	public ITextEditor getEditor_orNull() {
		return editor_opt;
	}
	
	/* ----------------- Hovers ----------------- */
	
	@Override
	public final ITextHover getTextHover(ISourceViewer sourceViewer, String contentType) {
		return getTextHover(sourceViewer, contentType, ITextViewerExtension2.DEFAULT_HOVER_STATE_MASK);
	}
	
	@Override
	public ITextHover getTextHover(ISourceViewer sourceViewer, String contentType, int stateMask) {
		return getBestMatchHover();
	}
	
	protected BestMatchHover getBestMatchHover() {
		return new BestMatchHover(sourceBuffer, getEditor_orNull());
	}
	
	@Override
	public IAnnotationHover getAnnotationHover(ISourceViewer sourceViewer) {
		return new HTMLAnnotationHover(false) {
			@Override
			protected boolean isIncluded(Annotation annotation) {
				return isShowInVerticalRuler(annotation);
			}
		};
	}
	
	@Override
	public IAnnotationHover getOverviewRulerAnnotationHover(ISourceViewer sourceViewer) {
		return new HTMLAnnotationHover(true) {
			@Override
			protected boolean isIncluded(Annotation annotation) {
				return isShowInOverviewRuler(annotation);
			}
		};
	}
	
	@Override
	public IInformationPresenter getInformationPresenter(ISourceViewer sourceViewer) {
		InformationPresenter presenter = new InformationPresenter(getInformationPresenterControlCreator(sourceViewer));
		presenter.setDocumentPartitioning(getConfiguredDocumentPartitioning(sourceViewer));
		
		// Register information providers
		for (String contentType : getConfiguredContentTypes(sourceViewer)) {
			presenter.setInformationProvider(getInformationProvider(contentType, sourceViewer), contentType);
		}
		
		presenter.setSizeConstraints(100, 12, false, true);
		return presenter;
	}
	
	// ================ Information provider
	
	@SuppressWarnings("unused")
	protected IInformationProvider getInformationProvider(String contentType, ISourceViewer sourceViewer) {
		return new HoverInformationProvider(getBestMatchHover());
	}
	
	protected IInformationControlCreator getInformationPresenterControlCreator(
			@SuppressWarnings("unused") ISourceViewer sourceViewer) {
		return new IInformationControlCreator() {
			@Override
			public IInformationControl createInformationControl(Shell parent) {
				return new DefaultInformationControl(parent, true);
			}
		};
	}
	
	/* ----------------- Navigation operations ----------------- */
	
	@Override 
	protected Map<String, IAdaptable> getHyperlinkDetectorTargets(ISourceViewer sourceViewer) {
		Map<String, IAdaptable> targets = super.getHyperlinkDetectorTargets(sourceViewer);
		targets.put(EditorSettings_Actual.EDITOR_CODE_TARGET, editor_opt);
		return targets;
	} 
	
	public void installOutlinePresenter(final LangSourceViewer sourceViewer) {
		
		InformationPresenter presenter = new InformationPresenter(getOutlinePresenterControlCreator(sourceViewer));
		
		presenter.setDocumentPartitioning(getConfiguredDocumentPartitioning(sourceViewer));
		presenter.setAnchor(AbstractInformationControlManager.ANCHOR_GLOBAL);
		
		IInformationProvider provider = new StructureElementInformationProvider(sourceBuffer); 
		
		for(String contentType : getConfiguredContentTypes(sourceViewer)) {
			presenter.setInformationProvider(provider, contentType);
		}
		
		presenter.setSizeConstraints(50, 20, true, false);
		
		presenter.install(sourceViewer);
		sourceViewer.setOutlinePresenter(presenter);
	}
	
	protected IInformationControlCreator getOutlinePresenterControlCreator(
			@SuppressWarnings("unused") ISourceViewer sourceViewer) {
		return new OutlineInformationControlCreator(this);
	}
	
	/* ----------------- Modification operations ----------------- */
	
	@Override
	public String[] getDefaultPrefixes(ISourceViewer sourceViewer, String contentType) {
		return new String[] { getToggleCommentPrefix(), "" };
	}
	
	protected abstract String getToggleCommentPrefix();
	
	@Override
	public IAutoEditStrategy[] getAutoEditStrategies(ISourceViewer sourceViewer, String contentType) {
		if(IDocument.DEFAULT_CONTENT_TYPE.equals(contentType)) {
			return array(new AutoEditStrategyAdapter(
				LangUIPlugin_Actual.createAutoEditStrategy(contentType, new VerifyKeyRecorder(sourceViewer))
			));
		} else {
			return super.getAutoEditStrategies(sourceViewer, contentType);
		}
	}
	
	@Override
	public String[] getIndentPrefixes(ISourceViewer sourceViewer, String contentType) {
		
		FormatterIndentMode indentMode = CodeFormatterConstants.fromPrefStore();
		int spaceIndentationSize = CodeFormatterConstants.FORMATTER_INDENTATION_SPACES_SIZE.get();
		String spaceIndent = TextSourceUtils.getNSpaces(spaceIndentationSize);
		
		// An empty string must be part of IndentPrefixes, so that empty lines do not fail the unindent operation.
		// for indent operation, only first element will be used, I believe 
		switch (indentMode) {
		case TAB: return array("\t", spaceIndent, ""); // return getIndentPrefixesForTab(spaceIndent); 
		case SPACES: return array(spaceIndent, "\t", ""); // return getIndentPrefixesForSpaces(spaceIndent);
		}
		
		throw assertUnreachable();
	}
	
	@Override
	protected void updateIndentationSettings(SourceViewer sourceViewer, String property) {
		super.updateIndentationSettings(sourceViewer, property);
		
		if(
			CodeFormatterConstants.FORMATTER_INDENTATION_SPACES_SIZE.key.equals(property) ||
			CodeFormatterConstants.FORMATTER_INDENT_MODE.key.equals(property)) {
			
			for(String contentType : getConfiguredContentTypes(sourceViewer)) {
				String[] prefixes= getIndentPrefixes(sourceViewer, contentType);
				sourceViewer.setIndentPrefixes(prefixes, contentType);
			}
		}
	}
	
	/* ----------------- Content Assist ----------------- */
	
	@Override
	public ContentAssistant getContentAssistant(ISourceViewer sourceViewer) {
		if(sourceViewer instanceof LangSourceViewer) {
			LangSourceViewer langSourceViewer = (LangSourceViewer) sourceViewer;
			
			ContentAssistantExt assistant = createContentAssitant(langSourceViewer);
			assistant.setDocumentPartitioning(getConfiguredDocumentPartitioning(sourceViewer));
			
			assistant.setRestoreCompletionProposalSize(LangUIPlugin.getDialogSettings("completion_proposal_size"));
			assistant.setInformationControlCreator(
				getInformationControl_ContentAsssist(getAdditionalInfoAffordanceString()));
			assistant.setContextInformationPopupOrientation(IContentAssistant.CONTEXT_INFO_ABOVE);
			assistant.enableColoredLabels(true);
			
			configureContentAssistantProcessors(assistant);
			// Note: configuration must come after processors are created
			assistant.configure();
			
			return assistant;
		}
		
		return null;
	}
	
	protected ContentAssistantExt createContentAssitant(LangSourceViewer langSourceViewer) {
		return new ContentAssistantExt(getPreferenceStore(), langSourceViewer);
	}
	
	protected void configureContentAssistantProcessors(ContentAssistantExt assistant) {
		Indexable<CompletionProposalsGrouping> categories = getContentAssistCategoriesProvider().getCategories();
		IContentAssistProcessor defaultCAP = createContentAssistProcessor(assistant, categories);
		for (LangPartitionTypes partitionType : LangPartitionTypes.values()) {
			
			IContentAssistProcessor cap;
			
			if(partitionType.getId().equals(IDocument.DEFAULT_CONTENT_TYPE)) {
				cap = defaultCAP;
			} else {
				// Setup a dummy content Assist processor, to prevent a platform NPE when returning null proposals
				// See: https://git.eclipse.org/r/#/c/76121/
				cap = new NullContentAssistProcessorExt();
			}
			
			assistant.setContentAssistProcessor(cap, partitionType.getId());
		}
	}
	
	protected LangContentAssistProcessor createContentAssistProcessor(ContentAssistantExt assistant,
			Indexable<CompletionProposalsGrouping> categories) {
		return new LangContentAssistProcessor(assistant, categories, sourceBuffer, getEditor_orNull());
	}
	
	protected abstract ContentAssistCategoriesBuilder getContentAssistCategoriesProvider();
	
	
	/* ----------------- reconciler ----------------- */ 
	
	@Override
	public IReconciler getReconciler(ISourceViewer sourceViewer) {
		return null; // not used, disable the textual spellchecker too
	}
	
	/* -----------------  ----------------- */
	
	@Override
	public void configureViewer(ProjectionViewerExt sourceViewer) {
		super.configureViewer(sourceViewer);
		
		if(sourceViewer instanceof LangSourceViewer) {
			LangSourceViewer langSourceViewer = (LangSourceViewer) sourceViewer;
			installOutlinePresenter(langSourceViewer);
		}
	}
	
}