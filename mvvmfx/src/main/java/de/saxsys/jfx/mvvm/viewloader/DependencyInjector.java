/*******************************************************************************
 * Copyright 2013 Alexander Casall, Manuel Mauky
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package de.saxsys.jfx.mvvm.viewloader;

import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import de.saxsys.jfx.mvvm.api.ViewModel;
import javafx.util.Callback;

import net.jodah.typetools.TypeResolver;
import de.saxsys.jfx.mvvm.api.InjectViewModel;
import de.saxsys.jfx.mvvm.base.view.View;

/**
 * This class handles the dependency injection for the mvvmFX framework.
 *
 * The main reason for this class is to make it possible for the user to use her own dependency injection
 * mechanism/framework. The user can define how instances should be retrieved by setting an callback that returns an
 * instance for a given class type (see {@link DependencyInjector#setCustomInjector}.
 *
 * @author manuel.mauky
 */
public class DependencyInjector {
	
	private Callback<Class<?>, Object> customInjector;
	
	private static DependencyInjector singleton = new DependencyInjector();
	
	DependencyInjector() {
	}
	
	public static DependencyInjector getInstance() {
		return singleton;
	}
	
	
	/**
	 * Define a custom injector that is used to retrieve instances. This can be used as a bridge to you dependency
	 * injection framework.
	 *
	 * The callback has to return an instance for the given class type. This is same way as it is done in the
	 * {@link javafx.fxml.FXMLLoader#setControllerFactory(javafx.util.Callback)} method.
	 *
	 * @param callback
	 *            the callback that returns instances of a specific class type.
	 */
	public void setCustomInjector(Callback<Class<?>, Object> callback) {
		this.customInjector = callback;
	}
	
	/**
	 * Returns an instance of the given type. When there is a custom injector defined (See:
	 * {@link #setCustomInjector(javafx.util.Callback)}) then this injector is used. Otherwise a new instance of the
	 * desired type is created. This is done by a call to {@link Class#newInstance()} which means that all constraints
	 * of the newInstance method are also need to be satisfied.
	 *
	 * @param type
	 * @param <T>
	 * @return
	 */
	<T> T getInstanceOf(Class<? extends T> type) {
		T instance = getUninitializedInstanceOf(type);
		
		if (instance instanceof View) {
			injectViewModel((View) instance);
		}
		
		return instance;
	}

	
	void injectViewModel(final View view) {
		final Class<?> viewModelType = TypeResolver.resolveRawArgument(View.class, view.getClass());
		final Field field = getViewModelField(view.getClass(), viewModelType);
		
		if(field != null){
			accessFieldAction(field, () -> {
				Object existingViewModel = field.get(view);

				if (existingViewModel == null) {
					Object viewModel = DependencyInjector.getInstance().getInstanceOf(viewModelType);
					field.setAccessible(true);
					field.set(view, viewModel);
				}

				return null;
			}, "Can't inject ViewModel of type <" + viewModelType
					+ "> into the view <" + view + ">");
		}
		
	}



	/**
	 * This method is used to get the ViewModel instance of a given view/codeBehind.
	 *
	 * @param view the view instance where the viewModel will be looked for.
	 * @param <ViewType> the generic type of the View
	 * @param <ViewModelType> the generic type of the ViewModel
	 * @return the ViewModel instance or null if no viewModel could be found.
	 */
	<ViewType extends View<? extends ViewModelType>, ViewModelType extends ViewModel> ViewModelType getViewModel(ViewType view){

		final Class<?> viewModelType = TypeResolver.resolveRawArgument(View.class, view.getClass());
		final Field field = getViewModelField(view.getClass(), viewModelType);

		ViewModelType viewModel = null;

		CompletableFuture<ViewModelType> future = new CompletableFuture<>();
		
		if(field != null){
			accessFieldAction(field, ()->{
				future.complete((ViewModelType) field.get(view));
				return null;
			}, "Can't get the viewModel of type <" + viewModelType + ">");
		}else{
			return null;
		}
		
		try{
			viewModel = future.get();
		} catch (InterruptedException | ExecutionException e) {
			throw new IllegalStateException(e);
		}

		return viewModel;
	}

	/**
	 * Helper method to execute a callback on a given field. This method encapsulates the error handling logic and the 
	 * handling of accessibility of the field.
	 */
	private void accessFieldAction(final Field field, final Callable<Void> callable, String errorMessage){
		AccessController.doPrivileged((PrivilegedAction<Object>) ()->{
			boolean wasAccessible = field.isAccessible();

			try {
				if (callable != null) {
					callable.call();
				}
			} catch (Exception exception){
				throw new IllegalStateException(errorMessage);
			} finally {
				field.setAccessible(wasAccessible);
			}

			return null;
		});
	}


	private Field getViewModelField(Class<?> viewType, Class<?> viewModelType) {
		
		for (Field field : viewType.getDeclaredFields()) {
			if (field.isAnnotationPresent(InjectViewModel.class)) {
				if (viewModelType != TypeResolver.Unknown.class && field.getType().isAssignableFrom(viewModelType)) {
					return field;
				}
			}
		}
		
		return null;
	}
	
	
	private <T> T getUninitializedInstanceOf(Class<? extends T> type) {
		if (isCustomInjectorDefined()) {
			return (T) customInjector.call(type);
		} else {
			try {
				// use default creation
				return type.newInstance();
			} catch (InstantiationException | IllegalAccessException e) {
				throw new RuntimeException("Can't create instance of type " + type.getName() +
						". Make sure that the class has a public no-arg constructor.", e);
			}
		}
	}
	
	/**
	 * See {@link #setCustomInjector(javafx.util.Callback)} for more details.
	 * 
	 * @return the defined custom injector if any
	 */
	Callback<Class<?>, Object> getCustomInjector() {
		return customInjector;
	}
	
	
	/**
	 * @return true when a custom injector is defined, otherwise false.
	 */
	boolean isCustomInjectorDefined() {
		return customInjector != null;
	}
	
}
