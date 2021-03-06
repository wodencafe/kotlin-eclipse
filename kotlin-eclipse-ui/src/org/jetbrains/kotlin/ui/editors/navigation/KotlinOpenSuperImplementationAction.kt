/*******************************************************************************

* Copyright 2000-2016 JetBrains s.r.o.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*******************************************************************************/
package org.jetbrains.kotlin.ui.editors.navigation

import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.jdt.internal.ui.actions.ActionMessages
import org.eclipse.jdt.ui.actions.IJavaEditorActionDefinitionIds
import org.eclipse.jdt.ui.actions.SelectionDispatchAction
import org.eclipse.jface.text.ITextSelection
import org.eclipse.jface.viewers.ArrayContentProvider
import org.eclipse.jface.window.Window
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.dialogs.ListDialog
import org.jetbrains.kotlin.core.model.KotlinAnalysisFileCache
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor.Kind.DECLARATION
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor.Kind.DELEGATION
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor.Kind.FAKE_OVERRIDE
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor.Kind.SYNTHESIZED
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.eclipse.ui.utils.EditorUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.OverridingUtil
import org.jetbrains.kotlin.ui.editors.KotlinCommonEditor
import org.jetbrains.kotlin.ui.overrideImplement.KotlinCallableLabelProvider
import java.util.LinkedHashSet

public class KotlinOpenSuperImplementationAction(val editor: KotlinCommonEditor) : SelectionDispatchAction(editor.site) {
    init {
        setActionDefinitionId(IJavaEditorActionDefinitionIds.OPEN_SUPER_IMPLEMENTATION)
        setText(ActionMessages.OpenSuperImplementationAction_label)
        setDescription(ActionMessages.OpenSuperImplementationAction_description)
    }
    
    companion object {
        val ACTION_ID = "OpenSuperImplementation"
    }
    
    override fun run(selection: ITextSelection) {
        val ktFile = editor.parsedFile
        val project = editor.javaProject
        if (ktFile == null || project == null) return
        
        val psiElement = EditorUtil.getPsiElement(editor, selection.offset)
        if (psiElement == null) return
        
        val declaration: KtDeclaration? = PsiTreeUtil.getParentOfType(psiElement, 
                KtNamedFunction::class.java,
                KtClass::class.java,
                KtProperty::class.java,
                KtObjectDeclaration::class.java)
        if (declaration == null) return
        
        val descriptor = resolveToDescriptor(declaration)
        if (descriptor !is DeclarationDescriptor) return
        
        val superDeclarations = findSuperDeclarations(descriptor)
        if (superDeclarations.isEmpty()) return
        
        val superDeclaration = when {
            superDeclarations.isEmpty() -> null
            superDeclarations.size == 1 -> superDeclarations.first()
            else -> chooseFromSelectionDialog(superDeclarations)
        }
        
        if (superDeclaration == null) return
        
        gotoElement(superDeclaration, declaration, editor, project)
    }
    
    private fun chooseFromSelectionDialog(declarations: Set<MemberDescriptor>): MemberDescriptor? {
        val shell = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell()
        val dialog = ListDialog(shell)
        
        dialog.setTitle("Super Declarations")
        dialog.setMessage("Select a declaration to navigate")
        dialog.setContentProvider(ArrayContentProvider())
        dialog.setLabelProvider(KotlinCallableLabelProvider())
        
        dialog.setInput(declarations.toTypedArray())
        
        if (dialog.open() == Window.CANCEL) {
            return null;
        }
        
        val result = dialog.getResult()
        if (result == null || result.size != 1) {
            return null
        }
        
        return result[0] as MemberDescriptor
    }
    
    private fun resolveToDescriptor(declaration: KtDeclaration): DeclarationDescriptor? {
        val context = KotlinAnalysisFileCache.getAnalysisResult(declaration.getContainingKtFile()).analysisResult.bindingContext
        return context[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]
    }
    
    private fun findSuperDeclarations(descriptor: DeclarationDescriptor): Set<MemberDescriptor> {
        val superDescriptors = when (descriptor) {
            is ClassDescriptor -> {
                descriptor.typeConstructor.supertypes.mapNotNull { 
                    val declarationDescriptor = it.constructor.declarationDescriptor
                    if (declarationDescriptor is ClassDescriptor) declarationDescriptor else null
                }.toSet()
            }
            
            is CallableMemberDescriptor -> descriptor.getDirectlyOverriddenDeclarations().toSet()
            
            else -> emptySet<MemberDescriptor>()
        }
        
        return superDescriptors
    }

    fun <D : CallableMemberDescriptor> D.getDirectlyOverriddenDeclarations(): Collection<D> {
        val result = LinkedHashSet<D>()
        for (overriddenDescriptor in overriddenDescriptors) {
            @Suppress("UNCHECKED_CAST")
            when (overriddenDescriptor.kind) {
                DECLARATION -> result.add(overriddenDescriptor as D)
                FAKE_OVERRIDE, DELEGATION -> result.addAll((overriddenDescriptor as D).getDirectlyOverriddenDeclarations())
                SYNTHESIZED -> {
                    //do nothing
                }
                else -> throw AssertionError("Unexpected callable kind ${overriddenDescriptor.kind}: $overriddenDescriptor")
            }
        }
        return OverridingUtil.filterOutOverridden(result)
    }
}