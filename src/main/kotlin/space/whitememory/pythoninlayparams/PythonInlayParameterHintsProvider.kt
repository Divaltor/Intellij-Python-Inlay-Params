package space.whitememory.pythoninlayparams

import com.intellij.codeInsight.hints.InlayInfo
import com.intellij.codeInsight.hints.InlayParameterHintsProvider
import com.intellij.codeInsight.hints.Option
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.*
import com.jetbrains.python.psi.types.TypeEvalContext


@Suppress("UnstableApiUsage")
class PythonInlayParameterHintsProvider : InlayParameterHintsProvider {

    companion object {
        val classHints = Option("hints.classes.parameters", { "Class hints" }, true)
        val functionHints = Option("hints.functions.parameters", { "Function hints" }, true)
        val lambdaHints = Option("hints.lambdas.parameters", { "Lambda hints" }, true)
    }

    private val forbiddenBuiltinFiles = setOf("builtins.pyi", "typing.pyi")

    override fun getDefaultBlackList() = setOf<String>()

    override fun isBlackListSupported() = false

    override fun getDescription() = "Help you pass correct arguments by showing parameter names at call sites"

    override fun getSupportedOptions() = listOf(classHints, functionHints, lambdaHints)

    override fun getProperty(key: String?): String? {
        val prefix = "inlay.parameters.hints"
        return when (key) {
            "$prefix.classes.parameters" -> "Show parameter names for class constructors and dataclasses."
            "$prefix.functions.parameters" -> "Show parameter names for function and method calls."
            "$prefix.lambdas.parameters" -> "Show parameter names for lambda calls."
            else -> null
        }
    }

    override fun getParameterHints(element: PsiElement): MutableList<InlayInfo> {
        val inlayInfos = mutableListOf<InlayInfo>()

        // This method gets every element in the editor,
        // so we have to verify it's a Python call expression
        if (element !is PyCallExpression || element is PyDecorator) {
            return inlayInfos
        }

        // Don't show hints if there's no arguments
        // Or the only argument is unpacking (*list, **dict)
        if (element.arguments.isEmpty() || (element.arguments.size == 1 && element.arguments[0] is PyStarArgument)) {
            return inlayInfos
        }

        // Try to resolve the object that made this call
        var resolved = element.callee?.reference?.resolve() ?: return inlayInfos
        if (isForbiddenBuiltinElement(resolved)) {
            return inlayInfos
        }

        var classAttributes = listOf<PyTargetExpression>()
        if (resolved is PyTargetExpression && lambdaHints.isEnabled()) {
            // TODO: Handle cases other than lambda expressions
            // Use the target to find the lambda expression object, and assign it to get its parameters up ahead
            resolved = PsiTreeUtil.getNextSiblingOfType(resolved, PyLambdaExpression::class.java) ?: return inlayInfos
        } else if (resolved is PyClass && classHints.isEnabled()) {
            // This call is made by a class (initialization), so we want to find the parameters it takes.
            // In order to do so, we first have to check for an init method, and if not found,
            // We will use the class attributes instead. (Handle dataclasses, attrs, etc.)
            val evalContext = TypeEvalContext.codeAnalysis(element.project, element.containingFile)
            val entryMethod = resolved.findInitOrNew(true, evalContext)

            resolved = if (entryMethod != null && entryMethod.containingClass == resolved) {
                entryMethod
            } else {
                // Use the class attributes if there's no init with params in the parent classes
                // TODO: Make sure wrong attributes are not used
                classAttributes = resolved.classAttributes
                entryMethod ?: resolved
            }
        } else if (!functionHints.isEnabled()) {
            return inlayInfos
        }

        val resolvedParameters = getElementFilteredParameters(resolved)
        val finalParameters = if (resolvedParameters.isEmpty() && classAttributes.isNotEmpty()) {
            // If there's no parameters in the object,
            // we use the class attributes instead,
            // in case this is a class
            classAttributes
        } else if (resolvedParameters.isEmpty()) {
            return inlayInfos
        } else {
            resolvedParameters
        }

        if (finalParameters.size == 1) {
            // Don't need a hint if there's only one parameter,
            // Make an exception for *args
            finalParameters[0].let {
                if (it !is PyNamedParameter || !it.isPositionalContainer) return inlayInfos
            }
        }

        finalParameters.zip(element.arguments).forEach { (param, arg) ->
            val paramName = param.name ?: return@forEach
            if (arg is PyStarArgument || arg is PyKeywordArgument) {
                // It's a keyword argument or unpacking,
                // we don't need to show hits after this
                return inlayInfos
            }

            if (param is PyNamedParameter) {
                if (param.isPositionalContainer) {
                    // This is an *args parameter that takes more than one argument
                    // So we show it and stop the further processing of this call expression
                    inlayInfos.add(InlayInfo("...$paramName", arg.textOffset))
                    return inlayInfos
                } else if (param.isKeywordContainer) {
                    // We don't want to show `kwargs` as a hint by accident
                    return inlayInfos
                }
            }

            if (isHintNameValid(paramName, arg)) {
                inlayInfos.add(InlayInfo(paramName, arg.textOffset))
            }
        }

        return inlayInfos
    }

    /**
     * Checks if the given parameter name is valid for the given argument.
     * This is used to skip parameters that start with __, or are the same as the argument.
     */
    private fun isHintNameValid(name: String, argument: PyExpression): Boolean {
        // TODO: More filters
        return name != argument.name?.lowercase() && !name.startsWith("__") && name.length > 1
    }

    /**
     * Get the parameters of the element, but filter out the ones that are not needed.
     * For example, if the element is a class method, we don't want to show the __self__ parameter.
     */
    private fun getElementFilteredParameters(element: PsiElement): List<PyParameter> {
        element.children.forEach {
            if (it is PyParameterList) {
                return it.parameters.filter { param -> !param.isSelf }
            }
        }
        return emptyList()
    }

    /**
     * Checks if the element is part of the standard library that isn't relevant for these hints.
     */
    private fun isForbiddenBuiltinElement(element: PsiElement): Boolean {
        // TODO: Implement using PyType.isBuiltin (?),
        //  although we still want some builtins like datetime.datetime
        return element.containingFile.name in forbiddenBuiltinFiles
    }
}